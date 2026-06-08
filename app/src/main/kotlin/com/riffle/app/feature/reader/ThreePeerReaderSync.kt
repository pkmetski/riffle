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
 * ABS audiobook progress as an **inbound-only** sync remote: [tryGet] reads `currentTime` and
 * bridges it through the SMIL so a genuinely-newer listen (another device / the ABS app) can win the
 * cycle and move the reader. [tryPatch] is a deliberate no-op — the cycle must never write the
 * audiobook, because an audio clock diverging from the page (readaloud can start behind) would then
 * drive the ebook and erase reading progress. Outbound to the audiobook is the separate, push-only
 * [ThreePeerReaderSyncCoordinator.pushAudiobookSeconds]; the feedback loop is closed by recording the
 * server timestamp that push returns (so our own write never reads back here as newer than local).
 */
internal class AbsAudiobookInboundRemote(
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

    // Inbound-only: outbound to the audiobook is pushAudiobookSeconds, never the reconcile cycle.
    override suspend fun tryPatch(canonical: CanonicalReaderPosition): Long? = null
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
                RemoteKind.ABS_AUDIO -> absAudioEndpoint?.let { AbsAudiobookInboundRemote(absApi, it, bridge) }
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
     * Push-only update of the matched ABS audiobook's `currentTime` from the live audio position
     * while readaloud plays. Writes ONLY the audiobook item — never the ebook, the reading position,
     * or a reader jump — so a behind/early audio clock can never erase or override reading progress
     * (the failure mode of every "audio drives the canonical" attempt). ABS gets the percentage too,
     * computed from the item's duration.
     *
     * Returns the server's `lastUpdate` for the write, or `null` on no-op / failure. The caller must
     * record it as the local timestamp: the inbound [AbsAudiobookInboundRemote] reads this same
     * record back next cycle, and only an equal-or-older read keeps the reading position the winner —
     * this is what closes the feedback loop without dropping inbound audiobook sync.
     */
    suspend fun pushAudiobookSeconds(seconds: Double): Long? {
        val ep = absAudioEndpoint ?: return null
        val payload = NetworkAudiobookProgressPayload(seconds.coerceAtLeast(0.0), ep.durationSec)
        val result = absApi.syncAudiobookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure)
        if (result !is NetworkSyncSessionResult.Success) return null
        // ABS's progress-sync response omits lastUpdate (it returns Success with no usable timestamp),
        // so we GET the record back to learn the server time our write was stored under. The caller
        // records it as the local timestamp; without it the inbound audiobook remote would read our
        // own push back as a newer remote and drive the ebook to the audio position.
        return (absApi.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkGetProgressResult.Success)
            ?.progress?.lastUpdate
    }
}
