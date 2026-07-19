package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.common.Clock
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.models.ServerProgress
import com.riffle.core.models.SessionPayload
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SyncSessionResult
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
                isFinished = null,
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
        val lastSyncedAt = positionStore.loadLastSyncedAt(source.id, itemId)
        // A row is CLEAN when nothing has changed locally since the last time we adopted a server
        // stamp (via LocalWins push or ServerWins pull). The server-clock and device-clock skew
        // means we can NOT rely on `localUpdatedAt > serverLastUpdate` alone — on a Device 2 whose
        // wall clock runs ahead of the ABS server clock, every local save uses
        // maxOf(now, existing+1) → local timestamps drift above the server's, and a subsequent
        // open would mistakenly pick LocalWins and push the stale local back over Device 1's fresh
        // server progress. Clean rows always adopt the server (nothing to preserve locally); dirty
        // rows keep the timestamp comparison (both stamps are the best we have) (#528).
        val localDirty = localUpdatedAt > lastSyncedAt
        val serverAdvanced = serverLastUpdate > lastSyncedAt

        return when {
            (!localDirty && serverAdvanced && serverProgress != null) ||
                (localDirty && serverLastUpdate > localUpdatedAt && serverProgress != null) -> {
                // Adopt the server's position AND stamp atomically, leaving the row CLEAN
                // (localUpdatedAt = lastSyncedAt = serverStamp). Prior code bumped only the
                // timestamp, leaving `positionStore.load()` returning the stale local locator
                // AND the row marked dirty — if the reader closed before the ServerLocator
                // UI-jump landed (fast back-out), `onClose` saved the stale locator with a
                // fresh `maxOf(now, existing+1)` stamp and the next sync-cycle decided
                // LocalWins on the (still) newer local stamp, pushing the stale 87% back over
                // the server's fresh 91% — the "Device 2 open silently downgraded Device 1's
                // progress" bug (#528).
                val serverLoc = serverProgress.ebookLocation.orEmpty()
                if (serverLoc.isNotEmpty()) {
                    positionStore.acceptServer(source.id, itemId, serverLoc, serverLastUpdate)
                } else {
                    // Server is a never-opened peer with no locator — still adopt the timestamp
                    // AND mark clean so we don't leave the row permanently dirty (which the sweep
                    // would re-pick up on every tick). Don't clobber the locally-persisted locator
                    // with "" — that's what markSyncedAt guarantees vs updateLocalTimestamp (#528).
                    positionStore.markSyncedAt(source.id, itemId, serverLastUpdate)
                }
                ProgressSyncCycleResult.ServerWins(
                    ServerProgress(
                        ebookLocation = serverLoc,
                        ebookProgress = serverProgress.ebookProgress,
                        lastUpdate = serverLastUpdate,
                    )
                )
            }
            // LocalWins only if we actually have local changes to push. A clean row (already in
            // sync with server) never gets pushed — even if device-clock skew made the timestamps
            // disagree, there's nothing new locally to send (#528).
            localDirty && localUpdatedAt > serverLastUpdate -> {
                val stamp = runCatching {
                    peer.pushEbookProgress(
                        itemId = itemId,
                        location = payload.ebookLocation,
                        progress = payload.ebookProgress,
                        isFinished = null,
                        lastUpdateEpochMs = clock.nowMs(),
                    )
                }.getOrNull()
                if (stamp != null) {
                    // Adopt the source-derived stamp; a zero/absent stamp falls back to clock so the
                    // row still marks clean (matches the old sessionApi.syncEbookProgress path).
                    // Use markSyncedAt to set BOTH stamps — prior code called updateLocalTimestamp
                    // which only advanced localUpdatedAt, leaving lastSyncedAt stale so the row
                    // stayed "dirty" per RoomDirtyProgressLedger's `localUpdatedAt > lastSyncedAt`
                    // query. The sweep then re-picked the row on every tick and re-PATCHed
                    // indefinitely, and the dirty-aware runSyncCycle comparison misclassified
                    // subsequent cross-device pushes as LocalWins-worthy (#528).
                    val ts = stamp.takeIf { it > 0L } ?: clock.nowMs()
                    positionStore.markSyncedAt(source.id, itemId, ts)
                }
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
                isFinished = null,
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
