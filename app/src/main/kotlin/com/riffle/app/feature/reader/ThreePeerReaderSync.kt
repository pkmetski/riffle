package com.riffle.app.feature.reader

import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.LocalCanonical
import com.riffle.core.domain.ProgressSyncStrategy
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.RemoteRead
import com.riffle.core.domain.SyncRemote
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkStorytellerPositionResult
import com.riffle.core.network.NetworkStorytellerPutResult
import com.riffle.core.network.NetworkSyncSessionResult
import com.riffle.core.network.StorytellerPositionApi

/**
 * Resolved ABS endpoint for a matched ABS Library Item (its media-progress record). [durationSec]
 * is the item's total audio length, sent with audiobook progress so ABS reports a real percentage;
 * 0 for the ebook endpoint.
 */
data class AbsSyncEndpoint(val baseUrl: String, val token: String, val insecure: Boolean, val itemId: String, val durationSec: Double = 0.0)

/** Resolved Storyteller endpoint for the matched readaloud book. */
data class StorytellerSyncEndpoint(val baseUrl: String, val token: String, val insecure: Boolean, val bookId: String)

// A reachable peer that has no position yet: it never wins (timestamp 0) but is stale relative
// to any local progress, so the cycle still pushes the reader's first position to it.
internal val EMPTY_REMOTE_READ = RemoteRead(CanonicalReaderPosition(""), 0L)

/** ABS ebook progress as a sync remote: an ebookLocation CFI on the ABS EPUB. */
internal class AbsEbookSyncRemote(
    private val api: AbsSessionApi,
    private val ep: AbsSyncEndpoint,
    private val bridge: ReaderPositionBridge,
) : SyncRemote {
    override val id = RemoteKind.ABS_EBOOK.name

    override suspend fun tryGet(): RemoteRead? {
        // NetworkError → null (unreachable, excluded); 404/no-CFI → reachable-empty placeholder.
        val p = (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress ?: return null
        val cfi = p.ebookLocation.takeIf { it.startsWith("epubcfi(") }
        if (p.lastUpdate <= 0L || cfi == null) return EMPTY_REMOTE_READ
        val canonical = bridge.absCfiToCanonical(cfi) ?: return EMPTY_REMOTE_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): Long? {
        val cfi = bridge.canonicalToAbsCfi(canonical.value) ?: return null
        val payload = NetworkEbookProgressPayload(cfi, bridge.canonicalBookProgress(canonical.value))
        if (api.syncEbookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure) !is NetworkSyncSessionResult.Success) return null
        // ABS's progress PATCH replies "OK" with no timestamp, so we GET the record to learn the
        // server time the write was stored under. The cycle adopts it; without it this write reads
        // back next cycle as a newer remote and bounces the reader (the "server overwrites local" bug).
        return (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress?.lastUpdate
    }
}

/**
 * ABS audiobook progress as a sync remote, translated through the readaloud bundle's SMIL media
 * overlay — the exact page↔audio-timestamp mapping. [tryGet] turns the audiobook `currentTime` into a
 * reading position (so a newer listen on another device moves the reader); [tryPatch] turns the
 * winning reading position into an audiobook `currentTime` (so reading advances the audiobook). The
 * SMIL times are made absolute over the concatenated audio files in [CanonicalPositionTranslator], so
 * `currentTime` matches the ABS audiobook's single-file timeline rather than a per-file offset.
 *
 * The raw audio *clock* is never written here — only positions derived from the canonical reading
 * position — so a behind/early playback clock cannot drive the ebook. The feedback loop is closed by
 * adopting the server timestamp each write returns (the cycle / push records it as the local time).
 */
internal class AbsAudiobookSyncRemote(
    private val api: AbsSessionApi,
    private val ep: AbsSyncEndpoint,
    private val bridge: ReaderPositionBridge,
) : SyncRemote {
    override val id = RemoteKind.ABS_AUDIO.name

    override suspend fun tryGet(): RemoteRead? {
        val p = (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress ?: return null
        if (p.lastUpdate <= 0L || p.currentTime <= 0.0) return EMPTY_REMOTE_READ // no audiobook position yet
        val canonical = bridge.audioSecondsToCanonical(p.currentTime) ?: return EMPTY_REMOTE_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): Long? {
        val seconds = bridge.canonicalToAudioSeconds(canonical.value) ?: return null
        val payload = NetworkAudiobookProgressPayload(seconds.coerceAtLeast(0.0), ep.durationSec)
        if (api.syncAudiobookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure) !is NetworkSyncSessionResult.Success) return null
        // ABS's PATCH returns no timestamp; GET the record back so the cycle adopts the real server
        // time (otherwise this write reads back next cycle as a newer remote — the feedback loop).
        return (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress?.lastUpdate
    }
}

/** Storyteller position as a sync remote: a Readium Locator on the Storyteller EPUB. */
internal class StorytellerSyncRemote(
    private val api: StorytellerPositionApi,
    private val ep: StorytellerSyncEndpoint,
    private val bridge: ReaderPositionBridge,
    private val pushTimestamp: Long,
) : SyncRemote {
    override val id = RemoteKind.STORYTELLER.name

    override suspend fun tryGet(): RemoteRead? {
        val r = api.getPosition(ep.baseUrl, ep.bookId, ep.token, ep.insecure)
        val (locatorJson, ts) = when (r) {
            is NetworkStorytellerPositionResult.Success -> r.locatorJson to r.timestampMillis
            is NetworkStorytellerPositionResult.NoPosition -> return EMPTY_REMOTE_READ
            is NetworkStorytellerPositionResult.NetworkError -> return null
        }
        if (locatorJson == null || ts <= 0L) return EMPTY_REMOTE_READ // reachable, no position yet
        val canonical = bridge.storytellerLocatorToCanonical(locatorJson) ?: return EMPTY_REMOTE_READ
        return RemoteRead(CanonicalReaderPosition(canonical), ts)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): Long? {
        val locator = bridge.canonicalToStorytellerLocator(canonical.value) ?: return null
        // Storyteller stores the timestamp we send, so the write reads back at exactly pushTimestamp.
        return if (api.putPosition(ep.baseUrl, ep.bookId, locator, pushTimestamp, ep.token, ep.insecure) is NetworkStorytellerPutResult.Success) {
            pushTimestamp
        } else null
    }
}

/**
 * Drives one matched readaloud book's three-peer reconciliation (ADR 0019). Builds the live
 * [SyncRemote]s for the applicable peer set and runs the unified [ProgressSyncStrategy] each
 * cycle. The canonical position is the displayed-EPUB Locator JSON; [runCycle] returns the
 * Locator JSON the reader should jump to, or `null` for no jump.
 *
 * The ABS ebook and audiobook remotes target their own matched ABS Library Item — the same item
 * when it is a combined ebook+audiobook, or two separate items when a library splits ebooks and
 * audiobooks (ADR 0019). A peer that can't be built (missing endpoint) is simply skipped.
 */
/**
 * @param jumpLocatorJson the displayed-EPUB Locator JSON to jump the reader to, or `null` for
 *   no jump. @param canonicalLastUpdate the winning timestamp to persist as `localUpdatedAt`.
 */
data class ThreePeerReaderCycleResult(val jumpLocatorJson: String?, val canonicalLastUpdate: Long)

class ThreePeerReaderSyncCoordinator(
    private val state: BookSyncState,
    private val bridge: ReaderPositionBridge,
    private val absApi: AbsSessionApi,
    private val storytellerApi: StorytellerPositionApi,
    // The ABS ebook and audiobook remotes target their own matched Library Item: one combined item
    // (same endpoint) or two separate items when a library splits ebooks and audiobooks (ADR 0019).
    private val absEbookEndpoint: AbsSyncEndpoint?,
    private val absAudioEndpoint: AbsSyncEndpoint?,
    private val storytellerEndpoint: StorytellerSyncEndpoint?,
) {
    suspend fun runCycle(displayedLocatorJson: String, localUpdatedAt: Long): ThreePeerReaderCycleResult {
        val strategy = ProgressSyncStrategy { kind ->
            when (kind) {
                RemoteKind.ABS_EBOOK -> absEbookEndpoint?.let { AbsEbookSyncRemote(absApi, it, bridge) }
                RemoteKind.ABS_AUDIO -> absAudioEndpoint?.let { AbsAudiobookSyncRemote(absApi, it, bridge) }
                RemoteKind.STORYTELLER -> storytellerEndpoint?.let { StorytellerSyncRemote(storytellerApi, it, bridge, localUpdatedAt) }
            }
        }
        val local = LocalCanonical(CanonicalReaderPosition(displayedLocatorJson), localUpdatedAt)
        val result = strategy.runCycle(state, local)
        // Guard the empty-remote placeholder: it never legitimately wins, but never jump to "".
        val jump = result.jumpTo?.value?.takeIf { it.isNotEmpty() }
        return ThreePeerReaderCycleResult(jump, result.canonicalLastUpdate)
    }

    /**
     * Responsive update of the matched ABS audiobook's `currentTime` from the current **reading
     * position**, translated through the bundle's SMIL ([ReaderPositionBridge.canonicalToAudioSeconds],
     * absolute over the concatenated audio files). Used by the audiobook-follow loop while readaloud
     * plays (the page tracks the audio) so the audiobook reaches the server between reconcile cycles.
     * Writes ONLY the audiobook item, from a page-derived position — never the raw audio clock — so it
     * can never erase or override reading progress.
     *
     * Returns the server's `lastUpdate` for the write, or `null` on no-op / failure. The caller must
     * record it as the local timestamp: the audiobook remote reads this same record back next cycle,
     * and only an equal-or-older read keeps the reading position the winner — this closes the feedback
     * loop without dropping inbound audiobook sync. ABS's PATCH carries no timestamp, so we GET it back.
     */
    suspend fun pushAudiobookProgress(canonicalLocatorJson: String): Long? =
        pushAudiobookAtSeconds(bridge.canonicalToAudioSeconds(canonicalLocatorJson))

    /**
     * Responsive audiobook update from the **exact narrated sentence** (the player's active fragment),
     * not the page — so while readaloud plays, the audiobook `currentTime` matches the sentence the
     * user hears, instead of lagging to the top of the page. Falls back to the page-derived position
     * when the fragment can't be placed.
     */
    suspend fun pushAudiobookForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Long? {
        val seconds = bridge.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { bridge.canonicalToAudioSeconds(it) }
        return pushAudiobookAtSeconds(seconds)
    }

    private suspend fun pushAudiobookAtSeconds(seconds: Double?): Long? {
        val ep = absAudioEndpoint ?: return null
        if (seconds == null) return null
        val payload = NetworkAudiobookProgressPayload(seconds.coerceAtLeast(0.0), ep.durationSec)
        if (absApi.syncAudiobookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure) !is NetworkSyncSessionResult.Success) return null
        return (absApi.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress?.lastUpdate
    }
}
