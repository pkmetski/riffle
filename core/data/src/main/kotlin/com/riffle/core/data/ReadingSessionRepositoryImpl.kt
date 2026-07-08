package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncSessionResult
import javax.inject.Inject

class ReadingSessionRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val sourceRepository: SourceRepository,
    private val positionStore: ReadingPositionStore,
    private val audiobookPositionStore: AudiobookPositionStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val libraryItemDao: LibraryItemDao,
    private val clock: Clock,
) : ReadingSessionRepository {

    override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
        val peer = activeProgressPeer() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active source or capability")
        )
        return try {
            peer.pushEbookProgress(
                itemId = itemId,
                location = payload.ebookLocation,
                progress = payload.ebookProgress,
                isFinished = false,
                lastUpdateEpochMs = clock.nowMs(),
            )
            SyncSessionResult.Success
        } catch (t: Throwable) {
            SyncSessionResult.NetworkError(t)
        }
    }

    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
        val source = sourceRepository.getActive() ?: return ProgressSyncCycleResult.Offline
        val catalog = catalogRegistry.forSource(source) ?: return ProgressSyncCycleResult.Offline
        val peer = catalog as? ProgressPeerCapability ?: return ProgressSyncCycleResult.Offline

        val serverProgress = runCatching { peer.pullProgress(itemId) }.getOrElse { return ProgressSyncCycleResult.Offline }
        val serverLastUpdate = serverProgress?.lastUpdate ?: 0L
        val localUpdatedAt = positionStore.loadLocalUpdatedAt(source.id, itemId)

        return when {
            serverLastUpdate > localUpdatedAt && serverProgress != null -> {
                positionStore.updateLocalTimestamp(source.id, itemId, serverLastUpdate)
                ProgressSyncCycleResult.ServerWins(
                    ServerProgress(
                        ebookLocation = serverProgress.ebookLocation.orEmpty(),
                        ebookProgress = serverProgress.ebookProgress,
                        lastUpdate = serverLastUpdate,
                    )
                )
            }
            localUpdatedAt > serverLastUpdate -> {
                val ok = runCatching {
                    peer.pushEbookProgress(
                        itemId = itemId,
                        location = payload.ebookLocation,
                        progress = payload.ebookProgress,
                        isFinished = false,
                        lastUpdateEpochMs = clock.nowMs(),
                    )
                }.isSuccess
                if (ok) positionStore.updateLocalTimestamp(source.id, itemId, clock.nowMs())
                ProgressSyncCycleResult.LocalWins
            }
            else -> ProgressSyncCycleResult.InSync
        }
    }

    override suspend fun touchOpenTimestamp(itemId: String) {
        val source = sourceRepository.getActive() ?: return
        val catalog = catalogRegistry.forSource(source) ?: return
        val peer = catalog as? ProgressPeerCapability ?: return
        val serverProgress = runCatching { peer.pullProgress(itemId) }.getOrNull() ?: return
        // Deliberately do NOT bump positionStore.localUpdatedAt here. Matching the server's
        // post-PATCH lastUpdate without also writing the server's cfi locally would create a
        // "local in sync but cfi empty" state: the next runSyncCycle would see equal stamps
        // and return InSync, the reader would open at page 1, and the next local save would
        // PATCH first-page state over the real server position. Leaving local untouched lets
        // the first runSyncCycle after this call see server > local and trigger ServerWins,
        // restoring the saved position to the navigator.
        runCatching {
            peer.pushEbookProgress(
                itemId = itemId,
                location = serverProgress.ebookLocation.orEmpty(),
                progress = serverProgress.ebookProgress,
                isFinished = false,
                lastUpdateEpochMs = clock.nowMs(),
            )
        }
    }

    override suspend fun markFinished(itemId: String, finished: Boolean) {
        val source = sourceRepository.getActive() ?: return
        // Read = keep the saved page; unread = clear it so the reader reopens at the start and the
        // 0 progress isn't contradicted by a stale cfi.
        val ebookLocation = if (finished) (positionStore.load(source.id, itemId) ?: "") else ""
        val now = clock.nowMs()
        if (!finished) {
            // Wipe EVERY local position store that could otherwise restore a stale position the next
            // time the book is opened (and re-save it, resurrecting the progress). The ebook reading
            // position alone isn't enough: a matched/readaloud book also resumes from the audiobook
            // position (translated back into an ebook locator on open) and the readaloud-resume row.
            positionStore.save(source.id, itemId, "")
            audiobookPositionStore.save(source.id, itemId, 0.0)
            audiobookPositionStore.updateLocalTimestamp(source.id, itemId, now)
            readaloudResumeStore.clear(source.id, itemId)
        }
        // Bump before catalog lookup: marks the record dirty so the sync cycle pushes it
        // even if the catalog is unavailable right now.
        positionStore.updateLocalTimestamp(source.id, itemId, now)
        libraryItemDao.updateFinishedAt(source.id, itemId, if (finished) now else null)
        val peer = catalogRegistry.forSource(source) as? ProgressPeerCapability ?: return
        // isFinished resets the audio half of the record too: true→progress 1, false→currentTime
        // and progress 0. Both halves move together so neither can re-shadow the other.
        runCatching {
            peer.pushEbookProgress(
                itemId = itemId,
                location = ebookLocation,
                progress = if (finished) 1.0f else 0.0f,
                isFinished = finished,
                lastUpdateEpochMs = now,
            )
        }
        // PATCH failure intentionally ignored — next sync cycle will push
    }

    private suspend fun activeProgressPeer(): ProgressPeerCapability? {
        val source = sourceRepository.getActive() ?: return null
        val catalog: Catalog = catalogRegistry.forSource(source) ?: return null
        return catalog as? ProgressPeerCapability
    }
}
