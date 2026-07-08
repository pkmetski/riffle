package com.riffle.app.feature.reader

import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.Clock
import com.riffle.core.domain.LocalCanonical
import com.riffle.core.domain.PositionTranslator
import com.riffle.core.domain.ProgressPeer
import com.riffle.core.domain.ProgressSyncStrategy
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.RemoteRead
import com.riffle.core.domain.WriteResult

/**
 * Resolved sync endpoint for a matched Library Item (its media-progress record). [durationSec] is
 * the item's total audio length, sent with audiobook progress so ABS reports a real percentage;
 * 0 for the ebook endpoint. [peer] is the Source's [ProgressPeerCapability] — sourced from the
 * Catalog for the item's Source.
 */
data class CatalogSyncEndpoint(
    val peer: ProgressPeerCapability,
    val itemId: String,
    val durationSec: Double = 0.0,
)

// A reachable peer that has no position yet: it never wins (timestamp 0) but is stale relative
// to any local progress, so the cycle still pushes the reader's first position to it.
internal val EMPTY_PEER_READ = RemoteRead(CanonicalReaderPosition(""), 0L)

/**
 * Push the matched audiobook's currentTime through [ProgressPeerCapability] and return the stamp
 * the caller should adopt. Historically ABS's PATCH replied with no timestamp so a follow-up GET
 * read back `lastUpdate`; that read-back is redundant when the caller controls the write time.
 */
internal suspend fun ProgressPeerCapability.writeAudiobookSeconds(
    ep: CatalogSyncEndpoint,
    seconds: Double,
    clock: Clock,
): Long? = runCatching {
    val now = clock.nowMs()
    val stamp = pushAudiobookProgress(
        itemId = ep.itemId,
        currentTimeSec = seconds.coerceAtLeast(0.0),
        durationSec = ep.durationSec,
        isFinished = false,
        lastUpdateEpochMs = now,
    )
    stamp?.takeIf { it > 0L } ?: now
}.getOrNull()

/** Ebook progress as a [ProgressPeer]: an ebookLocation CFI on the ABS EPUB. */
internal class AbsEbookProgressPeer(
    private val ep: CatalogSyncEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
) : ProgressPeer {
    override val id = RemoteKind.ABS_EBOOK.name

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
                isFinished = false,
                lastUpdateEpochMs = now,
            )
            // Adopt the source stamp when it reported one so a fresh write can't read back as newer
            // next cycle (feedback loop). A `null`/`0L` reply falls back to the client clock.
            WriteResult.Ok(stamp?.takeIf { it > 0L } ?: now)
        }.getOrElse { WriteResult.Failed("ABS ebook PATCH network error") }
    }
}

/**
 * Audiobook progress as a sync remote, translated through the readaloud bundle's SMIL media
 * overlay — the exact page↔audio-timestamp mapping. [tryGet] turns the audiobook `currentTime` into a
 * reading position (so a newer listen on another device moves the reader); [tryPatch] turns the
 * winning reading position into an audiobook `currentTime` (so reading advances the audiobook).
 */
internal class AbsAudiobookProgressPeer(
    private val ep: CatalogSyncEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
) : ProgressPeer {
    override val id = RemoteKind.ABS_AUDIO.name

    override suspend fun tryGet(): RemoteRead? {
        val p = runCatching { ep.peer.pullProgress(ep.itemId) }.getOrNull() ?: return null
        if (p.lastUpdate <= 0L || p.audioCurrentTime <= 0.0) return EMPTY_PEER_READ
        val canonical = translator.audioSecondsToCanonical(p.audioCurrentTime) ?: return EMPTY_PEER_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
        val seconds = translator.canonicalToAudioSeconds(canonical.value)
            ?: return WriteResult.Skipped
        val stamp = ep.peer.writeAudiobookSeconds(ep, seconds, clock)
            ?: return WriteResult.Failed("ABS audiobook PATCH failed")
        return WriteResult.Ok(stamp)
    }
}

private class InboundOnlyPeer(private val inner: ProgressPeer) : ProgressPeer {
    override val id = inner.id
    override suspend fun tryGet(): RemoteRead? = inner.tryGet()
    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult = WriteResult.Skipped
}

data class ReaderSyncCycleResult(val jumpLocatorJson: String?, val canonicalLastUpdate: Long)

data class AudioLedCycleResult(val jumpToAudioSec: Double?, val canonicalLastUpdate: Long)

class ReaderSyncCoordinator(
    private val state: BookSyncState,
    private val translator: PositionTranslator,
    private val clock: Clock,
    private val absEbookEndpoint: CatalogSyncEndpoint?,
    private val absAudioEndpoint: CatalogSyncEndpoint?,
) {
    suspend fun runCycle(displayedLocatorJson: String, localUpdatedAt: Long, pushAudio: Boolean = true): ReaderSyncCycleResult {
        val strategy = ProgressSyncStrategy { kind ->
            when (kind) {
                RemoteKind.ABS_EBOOK -> absEbookEndpoint?.let { AbsEbookProgressPeer(it, translator, clock) }
                RemoteKind.ABS_AUDIO -> absAudioEndpoint?.let {
                    val peer = AbsAudiobookProgressPeer(it, translator, clock)
                    if (pushAudio) peer else InboundOnlyPeer(peer)
                }
                RemoteKind.ABS_BOOKMARK -> null
            }
        }
        val local = LocalCanonical(CanonicalReaderPosition(displayedLocatorJson), localUpdatedAt)
        val result = strategy.runCycle(state, local)
        val jump = result.jumpTo?.value?.takeIf { it.isNotEmpty() }
        return ReaderSyncCycleResult(jump, result.canonicalLastUpdate)
    }

    suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult {
        val localCanonical = translator.audioSecondsToCanonical(currentAudioSec)
            ?: return AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = localUpdatedAt)
        val result = runCycle(localCanonical, localUpdatedAt)
        val jumpAudio = result.jumpLocatorJson?.let { translator.canonicalToAudioSeconds(it) }
        return AudioLedCycleResult(jumpToAudioSec = jumpAudio, canonicalLastUpdate = result.canonicalLastUpdate)
    }

    fun fragmentForCanonical(canonicalLocatorJson: String): String? =
        translator.canonicalToFragmentRef(canonicalLocatorJson)

    val hasAudioTarget: Boolean get() = absAudioEndpoint != null
    val audioItemId: String? get() = absAudioEndpoint?.itemId
    val ebookItemId: String? get() = absEbookEndpoint?.itemId

    fun audioSecondsForCanonical(canonicalLocatorJson: String): Double? =
        translator.canonicalToAudioSeconds(canonicalLocatorJson)

    fun audioSecondsForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Double? =
        translator.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { translator.canonicalToAudioSeconds(it) }

    fun fragmentForAudioSeconds(seconds: Double): String? = translator.fragmentForAudioSeconds(seconds)

    fun canonicalForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToCanonical(seconds)

    fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
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
        val ep = absAudioEndpoint ?: return null
        if (seconds == null) return null
        return ep.peer.writeAudiobookSeconds(ep, seconds, clock)
    }
}

class AudiobookFollow(
    private val endpoint: CatalogSyncEndpoint,
    private val translator: PositionTranslator,
    private val clock: Clock,
    val sourceId: String,
    val audioItemId: String,
    val ebookItemId: String? = null,
    private val quotes: Map<String, com.riffle.core.domain.SentenceQuote> = emptyMap(),
) {
    fun secondsForFragment(fragmentRef: String): Double? = translator.fragmentRefToAudioSeconds(fragmentRef)

    fun fragmentForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToStorytellerProgression(seconds)?.let { translator.fragmentAt(it) }

    fun ebookLocatorForAudioSeconds(seconds: Double): String? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val quote = quotes[ref.substringAfter('#')] ?: return null
        return readaloudLocatorJson(ref, quote).toString()
    }

    suspend fun pushFragment(fragmentRef: String): Long? =
        secondsForFragment(fragmentRef)?.let { endpoint.peer.writeAudiobookSeconds(endpoint, it, clock) }

    fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val href = ref.substringBefore('#').takeIf { it.isNotEmpty() } ?: return null
        return com.riffle.core.domain.ReadaloudResumePosition(href = href, progression = null, fragmentRef = ref)
    }
}
