package com.riffle.feature.reader

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.common.Clock
import com.riffle.core.domain.LocalCanonical
import com.riffle.core.domain.PositionTranslator
import com.riffle.core.domain.ProgressPeer
import com.riffle.core.domain.ProgressSyncStrategy
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.RemoteRead
import com.riffle.core.domain.WriteResult

/**
 * Resolved ebook sync endpoint for a matched Library Item. [peer] is the Source's
 * [ProgressPeerCapability] — sourced from the Catalog for the item's Source.
 */
data class CatalogEbookEndpoint(
    val peer: ProgressPeerCapability,
    val itemId: String,
)

/**
 * Resolved audiobook sync endpoint for a matched Library Item.
 */
data class CatalogAudioEndpoint(
    val peer: AudiobookProgressPeerCapability,
    val itemId: String,
    val durationSec: Double = 0.0,
)

// A reachable peer that has no position yet.
internal val EMPTY_PEER_READ = RemoteRead(CanonicalReaderPosition(""), 0L)

/**
 * Push the matched audiobook's currentTime through [AudiobookProgressPeerCapability].
 */
internal suspend fun AudiobookProgressPeerCapability.writeAudiobookSeconds(
    itemId: String,
    durationSec: Double,
    seconds: Double,
    clock: Clock,
): Long? = runCatching {
    val now = clock.nowMs()
    val stamp = pushAudiobookProgress(
        itemId = itemId,
        currentTimeSec = seconds.coerceAtLeast(0.0),
        durationSec = durationSec,
        isFinished = null,
        lastUpdateEpochMs = now,
    )
    stamp?.takeIf { it > 0L } ?: now
}.getOrNull()

/** Ebook progress as a [ProgressPeer]: a locator (dialect-specific) on the ebook peer. */
internal class EbookProgressPeer(
    private val ep: CatalogEbookEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
) : ProgressPeer {
    override val id = RemoteKind.EBOOK_POSITION.name

    override suspend fun tryGet(): RemoteRead? {
        val p = runCatching { ep.peer.pullProgress(ep.itemId) }.getOrNull() ?: return null
        val cfi = p.ebookLocation?.takeIf { it.startsWith("epubcfi(") }
        if (p.lastUpdate <= 0L || cfi == null) return EMPTY_PEER_READ
        val canonical = translator.absCfiToCanonical(cfi) ?: return EMPTY_PEER_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
        val cfi = translator.canonicalToAbsCfi(canonical.value)
            ?: return WriteResult.Skipped
        val progress = translator.canonicalBookProgress(canonical.value)
        return runCatching {
            val now = clock.nowMs()
            val stamp = ep.peer.pushEbookProgress(
                itemId = ep.itemId,
                location = cfi,
                progress = progress,
                isFinished = null,
                lastUpdateEpochMs = now,
            )
            WriteResult.Ok(stamp?.takeIf { it > 0L } ?: now)
        }.getOrElse { WriteResult.Failed("ebook PATCH network error") }
    }
}

/**
 * Audiobook progress as a sync remote, translated through the readaloud bundle's SMIL media
 * overlay.
 */
internal class AudiobookProgressPeerAdapter(
    private val ep: CatalogAudioEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
) : ProgressPeer {
    override val id = RemoteKind.AUDIO_POSITION.name

    override suspend fun tryGet(): RemoteRead? {
        val p = runCatching { ep.peer.pullProgress(ep.itemId) }.getOrNull() ?: return null
        if (p.lastUpdate <= 0L || p.audioCurrentTime <= 0.0) return EMPTY_PEER_READ
        val canonical = translator.audioSecondsToCanonical(p.audioCurrentTime) ?: return EMPTY_PEER_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
        val seconds = translator.canonicalToAudioSeconds(canonical.value)
            ?: return WriteResult.Skipped
        val stamp = ep.peer.writeAudiobookSeconds(ep.itemId, ep.durationSec, seconds, clock)
            ?: return WriteResult.Failed("audiobook PATCH failed")
        return WriteResult.Ok(stamp)
    }
}

private class InboundOnlyPeer(private val inner: ProgressPeer) : ProgressPeer {
    override val id = inner.id
    override suspend fun tryGet(): RemoteRead? = inner.tryGet()
    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult = WriteResult.Skipped
}

data class ReaderSyncCycleResult(val jumpLocatorJson: String?, val canonicalLastUpdate: Long)

class ReaderSyncCoordinator(
    private val state: BookSyncState,
    private val translator: PositionTranslator,
    private val clock: Clock,
    private val ebookEndpoint: CatalogEbookEndpoint?,
    private val audioEndpoint: CatalogAudioEndpoint?,
) : ReaderSyncCoordinatorInterface {
    suspend fun runCycle(displayedLocatorJson: String, localUpdatedAt: Long, pushAudio: Boolean = true): ReaderSyncCycleResult {
        val strategy = ProgressSyncStrategy { kind ->
            when (kind) {
                RemoteKind.EBOOK_POSITION -> ebookEndpoint?.let { EbookProgressPeer(it, translator, clock) }
                RemoteKind.AUDIO_POSITION -> audioEndpoint?.let {
                    val peer = AudiobookProgressPeerAdapter(it, translator, clock)
                    if (pushAudio) peer else InboundOnlyPeer(peer)
                }
                RemoteKind.AUDIOBOOK_BOOKMARK -> null
            }
        }
        val local = LocalCanonical(CanonicalReaderPosition(displayedLocatorJson), localUpdatedAt)
        val result = strategy.runCycle(state, local)
        val jump = result.jumpTo?.value?.takeIf { it.isNotEmpty() }
        return ReaderSyncCycleResult(jump, result.canonicalLastUpdate)
    }

    override suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult {
        val localCanonical = translator.audioSecondsToCanonical(currentAudioSec)
            ?: return AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = localUpdatedAt)
        val result = runCycle(localCanonical, localUpdatedAt)
        val jumpAudio = result.jumpLocatorJson?.let { translator.canonicalToAudioSeconds(it) }
        return AudioLedCycleResult(jumpToAudioSec = jumpAudio, canonicalLastUpdate = result.canonicalLastUpdate)
    }

    fun fragmentForCanonical(canonicalLocatorJson: String): String? =
        translator.canonicalToFragmentRef(canonicalLocatorJson)

    val hasAudioTarget: Boolean get() = audioEndpoint != null
    val audioItemId: String? get() = audioEndpoint?.itemId
    override val ebookItemId: String? get() = ebookEndpoint?.itemId

    fun audioSecondsForCanonical(canonicalLocatorJson: String): Double? =
        translator.canonicalToAudioSeconds(canonicalLocatorJson)

    fun audioSecondsForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Double? =
        translator.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { translator.canonicalToAudioSeconds(it) }

    fun fragmentForAudioSeconds(seconds: Double): String? = translator.fragmentForAudioSeconds(seconds)

    override fun canonicalForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToCanonical(seconds)

    override fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
        val canonicalJson = translator.audioSecondsToCanonical(seconds) ?: return null
        val pos = CanonicalReaderPosition(canonicalJson)
        val href = pos.href ?: return null
        return com.riffle.core.domain.ReadaloudResumePosition(
            href = href,
            progression = pos.chapterProgression,
            fragmentRef = translator.canonicalToFragmentRef(canonicalJson),
        )
    }

    fun bundleFragmentRefForSelection(displayedRef: String): String? {
        val spanId = displayedRef.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        val bundleHref = translator.displayedHrefToBundleHref(displayedRef.substringBefore('#')) ?: return null
        return "$bundleHref#$spanId"
    }

    suspend fun pushAudiobookProgress(canonicalLocatorJson: String): Long? =
        pushAudiobookAtSeconds(translator.canonicalToAudioSeconds(canonicalLocatorJson))

    suspend fun pushAudiobookForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Long? {
        val seconds = translator.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { translator.canonicalToAudioSeconds(it) }
        return pushAudiobookAtSeconds(seconds)
    }

    private suspend fun pushAudiobookAtSeconds(seconds: Double?): Long? {
        val ep = audioEndpoint ?: return null
        if (seconds == null) return null
        return ep.peer.writeAudiobookSeconds(ep.itemId, ep.durationSec, seconds, clock)
    }
}

class AudiobookFollow(
    private val endpoint: CatalogAudioEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
    val sourceId: String,
    val audioItemId: String,
    override val ebookItemId: String? = null,
    private val quotes: Map<String, com.riffle.core.domain.SentenceQuote> = emptyMap(),
) : AudiobookFollowInterface {
    fun secondsForFragment(fragmentRef: String): Double? = translator.fragmentRefToAudioSeconds(fragmentRef)

    fun fragmentForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToStorytellerProgression(seconds)?.let { translator.fragmentAt(it) }

    override fun ebookLocatorForAudioSeconds(seconds: Double): String? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val quote = quotes[ref.substringAfter('#')] ?: return null
        return buildReadaloudLocatorJson(ref, quote)
    }

    suspend fun pushFragment(fragmentRef: String): Long? =
        secondsForFragment(fragmentRef)?.let {
            endpoint.peer.writeAudiobookSeconds(endpoint.itemId, endpoint.durationSec, it, clock)
        }

    override fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val href = ref.substringBefore('#').takeIf { it.isNotEmpty() } ?: return null
        return com.riffle.core.domain.ReadaloudResumePosition(href = href, progression = null, fragmentRef = ref)
    }
}

/**
 * Builds a Readium Locator JSON string for a readaloud sentence anchor. Pure string construction
 * (no org.json dependency) so it is usable in JVM source sets outside the Android module.
 *
 * The format produced matches [com.riffle.app.feature.reader.readaloudLocatorJson] exactly:
 * text-anchored highlight + cssSelector fast path when a fragment id is present.
 */
internal fun buildReadaloudLocatorJson(ref: String, quote: SentenceQuote?): String {
    val hashIdx = ref.indexOf('#')
    val href = if (hashIdx >= 0) ref.substring(0, hashIdx) else ref
    val fragId = if (hashIdx >= 0) ref.substring(hashIdx + 1) else null

    fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"")

    val locations = if (fragId != null) {
        """{"fragments":["${fragId.escapeJson()}"],"cssSelector":"#${fragId.escapeJson()}"}"""
    } else {
        "{}"
    }

    val base = """{"href":"${href.escapeJson()}","type":"application/xhtml+xml","locations":$locations"""

    return if (quote != null) {
        val text = """{"before":"${quote.before.escapeJson()}","highlight":"${quote.highlight.escapeJson()}","after":"${quote.after.escapeJson()}"}"""
        "$base,\"text\":$text}"
    } else {
        "$base}"
    }
}
