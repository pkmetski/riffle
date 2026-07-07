package com.riffle.app.feature.audiobook

import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.SyncPositionStore
import javax.inject.Inject

/**
 * Owns the two-peer reconciliation cycle for a matched ABS audiobook (ADR 0019, 0029):
 *
 * - the [ReaderSyncCoordinator] full-coordinator attach (bundle + ABS EPUB + cross-EPUB index),
 * - the bundle-only [AudiobookFollow] fallback when the index isn't built yet,
 * - the audiobook→ebook `readingSyncStore` mirror (ADR 0030),
 * - the audiobook→readaloud `readaloudResumeStore` mirror (ADR 0031).
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 3). The coordinator is per-player;
 * it holds mutable references to the attached sync so a self-heal mid-session can promote from
 * fallback to full without threading state through the VM.
 */
class AudiobookReconciliationCoordinator @Inject constructor(
    private val readerSyncFactory: ReaderSyncFactory,
    private val openReconcileTargets: OpenReconcileTargets,
    private val audioSyncStore: SyncPositionStore<Double>,
    private val readingSyncStore: SyncPositionStore<String>,
    private val readaloudResumeStore: ReadaloudResumeStore,
) {
    private var _readerSync: ReaderSyncCoordinator? = null
    private var _audiobookFollow: AudiobookFollow? = null

    /** The attached full coordinator, or null if not yet attached (or unavailable for this book). */
    val readerSync: ReaderSyncCoordinator? get() = _readerSync

    /**
     * The ebook ABS item id currently marked open by either the full coordinator or the bundle-only
     * fallback — for the [OpenReconcileTargets.markClosed] call in the VM's `onCleared`. Null when
     * neither is attached (audiobook-only, or ebook item id unknown).
     */
    val ebookItemIdForMarkClosed: String? get() = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId

    /** Outcome of an [attach] call. */
    data class AttachResult(
        /** True when this call promoted from "no sync" to the full [ReaderSyncCoordinator]. */
        val readerSyncAttached: Boolean,
        /** The audio-seconds an inbound remote win asks the player to seek to; null when no jump. */
        val jumpToAudioSec: Double?,
        /** Timestamp the caller must adopt as `localUpdatedAt` (from the cycle result). */
        val canonicalLastUpdate: Long,
    )

    /**
     * Attach the matched two-peer cycle once its prerequisites (bundle + ABS EPUB + cross-EPUB
     * index) are cached, running the open-reconcile once. Idempotent — a second call is a no-op
     * unless the fallback is upgraded to the full coordinator. Called on open and re-tried each
     * follow-loop tick so the ebook starts syncing as soon as the background index build finishes
     * (ADR 0029).
     *
     * @param atUpdatedAt seed timestamp for the first cycle. 0 keeps the cycle inbound-only (so a
     *   not-yet-advanced local position never leads); a real stamp lets a genuinely-newer local
     *   listen lead.
     */
    suspend fun attach(
        sourceId: String,
        itemId: String,
        atSec: Double,
        atUpdatedAt: Long,
    ): AttachResult {
        if (_readerSync != null || sourceId.isEmpty()) {
            return AttachResult(
                readerSyncAttached = false,
                jumpToAudioSec = null,
                canonicalLastUpdate = atUpdatedAt,
            )
        }
        val rs = readerSyncFactory.createIfApplicable(itemId)
        if (rs == null) {
            // No cross-EPUB index yet — fall back to the bundle-only follow so audiobook→ebook and
            // audiobook→readaloud still sync via the bundle, index-free (ADR 0031).
            if (_audiobookFollow == null) {
                _audiobookFollow = runCatching {
                    readerSyncFactory.createAudiobookFollowIfApplicable(itemId)
                }.getOrNull()
                _audiobookFollow?.ebookItemId?.let { openReconcileTargets.markOpen(sourceId, it) }
            }
            return AttachResult(
                readerSyncAttached = false,
                jumpToAudioSec = null,
                canonicalLastUpdate = atUpdatedAt,
            )
        }
        _readerSync = rs
        // Matched: this player also drives the ebook ABS record, so the sweep must skip that
        // (possibly split-library) item too while the player is open (ADR 0030).
        rs.ebookItemId?.let { openReconcileTargets.markOpen(sourceId, it) }
        val r = rs.runAudioLedCycle(atSec, atUpdatedAt)
        return AttachResult(
            readerSyncAttached = true,
            jumpToAudioSec = r.jumpToAudioSec,
            canonicalLastUpdate = r.canonicalLastUpdate,
        )
    }

    /**
     * Dual-write the counterpart reading position locally (ADR 0030), the symmetric twin of the
     * reader's mirror. For a matched book the just-saved listen position is translated through the
     * bundle's SMIL into the ebook locator and persisted into the reading store under the ebook's
     * own ABS item id, stamped with the audiobook row's current (localUpdatedAt, lastSyncedAt) so
     * both rows share dirty state. Pure additive write to the sibling row. No-op unless matched and
     * translatable.
     */
    suspend fun mirrorListeningToReading(sourceId: String, itemId: String, seconds: Double) {
        if (sourceId.isEmpty()) return
        val ebookItemId = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId ?: return
        // Index-free first (text-anchored, via the bundle), then the index-based canonical if
        // present (ADR 0031: audiobook→ebook goes via the bundle, never requiring the cross-EPUB
        // index).
        val ebookLocator = _audiobookFollow?.ebookLocatorForAudioSeconds(seconds)
            ?: _readerSync?.canonicalForAudioSeconds(seconds)
            ?: return
        val snap = audioSyncStore.snapshot(sourceId, itemId)
        readingSyncStore.mirror(sourceId, ebookItemId, ebookLocator, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    /**
     * Dual-write the readaloud resume from the listen position (ADR 0031): map the listen seconds
     * to the narrated sentence and persist it under the ebook item id, so reopening the reader and
     * pressing Play resumes on that sentence instead of the stale saved one. Works index-free via
     * the bundle SMIL when the full coordinator isn't built. No-op unless matched and the seconds
     * narrate a sentence.
     */
    suspend fun writeListeningToReadaloud(sourceId: String, itemId: String, seconds: Double) {
        if (sourceId.isEmpty()) return
        val ebookItemId = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId ?: return
        val anchor = _readerSync?.readaloudAnchorForAudioSeconds(seconds)
            ?: _audiobookFollow?.readaloudAnchorForAudioSeconds(seconds)
            ?: return
        readaloudResumeStore.save(sourceId, ebookItemId, anchor)
    }
}
