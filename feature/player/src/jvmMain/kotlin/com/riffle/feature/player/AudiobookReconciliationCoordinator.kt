package com.riffle.feature.player

import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.SyncPositionStore
import com.riffle.feature.reader.AudiobookFollowInterface
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import com.riffle.feature.reader.ReaderSyncFactoryInterface

/**
 * Owns the two-peer reconciliation cycle for a matched ABS audiobook (ADR 0023, 0029):
 *
 * - the [ReaderSyncCoordinator] full-coordinator attach (bundle + ABS EPUB + cross-EPUB index),
 * - the bundle-only [AudiobookFollow] fallback when the index isn't built yet,
 * - the audiobook→ebook `readingSyncStore` mirror (ADR 0036),
 * - the audiobook→readaloud `readaloudResumeStore` mirror (ADR 0037).
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 3).
 */
class AudiobookReconciliationCoordinator constructor(
    private val readerSyncFactory: ReaderSyncFactoryInterface,
    private val openReconcileTargets: OpenReconcileTargets,
    private val audioSyncStore: SyncPositionStore<Double>,
    private val readingSyncStore: SyncPositionStore<String>,
    private val readaloudResumeStore: ReadaloudResumeStore,
) {
    private var _readerSync: ReaderSyncCoordinatorInterface? = null
    private var _audiobookFollow: AudiobookFollowInterface? = null

    val readerSync: ReaderSyncCoordinatorInterface? get() = _readerSync

    val ebookItemIdForMarkClosed: String? get() = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId

    data class AttachResult(
        val readerSyncAttached: Boolean,
        val jumpToAudioSec: Double?,
        val canonicalLastUpdate: Long,
    )

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
        rs.ebookItemId?.let { openReconcileTargets.markOpen(sourceId, it) }
        val r = rs.runAudioLedCycle(atSec, atUpdatedAt)
        return AttachResult(
            readerSyncAttached = true,
            jumpToAudioSec = r.jumpToAudioSec,
            canonicalLastUpdate = r.canonicalLastUpdate,
        )
    }

    suspend fun mirrorListeningToReading(sourceId: String, itemId: String, seconds: Double) {
        if (sourceId.isEmpty()) return
        val ebookItemId = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId ?: return
        val ebookLocator = _audiobookFollow?.ebookLocatorForAudioSeconds(seconds)
            ?: _readerSync?.canonicalForAudioSeconds(seconds)
            ?: return
        val snap = audioSyncStore.snapshot(sourceId, itemId)
        readingSyncStore.mirror(sourceId, ebookItemId, ebookLocator, snap.localUpdatedAt, snap.lastSyncedAt)
    }

    suspend fun writeListeningToReadaloud(sourceId: String, itemId: String, seconds: Double) {
        if (sourceId.isEmpty()) return
        val ebookItemId = _readerSync?.ebookItemId ?: _audiobookFollow?.ebookItemId ?: return
        val anchor = _readerSync?.readaloudAnchorForAudioSeconds(seconds)
            ?: _audiobookFollow?.readaloudAnchorForAudioSeconds(seconds)
            ?: return
        readaloudResumeStore.save(sourceId, ebookItemId, anchor)
    }
}
