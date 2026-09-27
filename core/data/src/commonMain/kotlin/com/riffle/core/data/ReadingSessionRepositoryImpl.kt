package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
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
import com.riffle.core.domain.isFinishedReadingProgress
import com.riffle.core.domain.keepsFinishedState

class ReadingSessionRepositoryImpl constructor(
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
        val source = sourceRepository.getActive() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active source or capability")
        )
        // Same guard as runSyncCycle's LocalWins branch: a cover-only visit to a finished book
        // must not push a ~0 fraction that un-finishes it on the source.
        val localDbProgress = libraryItemDao.getById(source.id, itemId)?.readingProgress ?: 0f
        if (keepsFinishedState(localDbProgress, payload.ebookProgress)) return SyncSessionResult.Success
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
                // A finished server record with no location is a mark-as-read reset, not a
                // position to jump to: surfacing it as ServerWins made the EPUB reader fall back
                // to locateProgression(1.0) and open a freshly marked-read book at the back cover.
                // The stamp was adopted above so the row is clean; only the UI jump is withheld.
                if (serverLoc.isEmpty() && isFinishedReadingProgress(serverProgress.ebookProgress)) {
                    return ProgressSyncCycleResult.InSync
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
                // Opening a marked-as-read book and sitting on the cover must not push a ~0
                // fraction: pullAllProgress derives isFinished from ebookProgress, so that push
                // would move the book out of Completed without any reading. Reading past the
                // cover pushes normally and un-finishes the book (see keepsFinishedState).
                val localDbProgress = libraryItemDao.getById(source.id, itemId)?.readingProgress ?: 0f
                if (keepsFinishedState(localDbProgress, payload.ebookProgress)) return ProgressSyncCycleResult.InSync
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
                    // stayed "dirty" per DaoDirtyProgressLedger's `localUpdatedAt > lastSyncedAt`
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
        val now = clock.nowMs()
        // Wipe EVERY local position store in both directions so the reader always reopens at the
        // start: mark-as-read = done, begin from scratch next time; mark-as-unread = start over.
        // Preserving the position on mark-as-read caused the detail screen to show the old audio
        // position (e.g. 47%) while the library showed 100%, because the local audiobookPositionStore
        // fed a contradicting current-time that was never reset.
        positionStore.save(source.id, itemId, "")
        audiobookPositionStore.save(source.id, itemId, 0.0)
        readaloudResumeStore.clear(source.id, itemId)
        if (!finished) {
            // For mark-as-unread, also dirty the audio row so the sync sweeper pushes currentTime=0
            // to the source and resets the audio dimension server-side too. For mark-as-read we
            // skip this: pushEbookProgress(isFinished=true) below already resets the server record;
            // bumping the audio timestamp would cause a spurious currentTime=0 sync on top of that.
            audiobookPositionStore.updateLocalTimestamp(source.id, itemId, now)
        }
        // Bump before catalog lookup: marks the ebook record dirty so the sync cycle pushes it
        // even if the catalog is unavailable right now.
        positionStore.updateLocalTimestamp(source.id, itemId, now)
        // Immediately reflect the mark-read/unread in the library DB so the grid shows the correct
        // value before the next pullAllProgress sweep. Without this, the library stays at the old
        // progress until either the sweeper makes the row clean AND the post-loop runs — during that
        // window the library shows stale progress even though the intent is 100% / 0%.
        // Stamp with `now` so this explicit mark wins over any stale in-flight server pull
        // (last-update-wins); the source push below refreshes the server stamp too.
        libraryItemDao.updateReadingProgressStamped(source.id, itemId, if (finished) 1.0f else 0.0f, now)
        libraryItemDao.updateFinishedAt(source.id, itemId, if (finished) now else null)
        val catalog = catalogRegistry.forSource(source) ?: return
        val peer = catalog as? ProgressPeerCapability ?: return
        // isFinished resets the audio half of the record too: true→progress 1, false→currentTime
        // and progress 0. Both halves move together so neither can re-shadow the other.
        // Always send location="" — the reader must open from the beginning after a mark-read/unread.
        val ebookStamp = runCatching {
            peer.pushEbookProgress(
                itemId = itemId,
                location = "",
                progress = if (finished) 1.0f else 0.0f,
                isFinished = finished,
                lastUpdateEpochMs = now,
            )
        }.getOrNull()
        // PATCH failure intentionally ignored — next sync cycle will push

        if (finished) {
            // The unconditional audiobookPositionStore.save(0.0) above marks the audio row dirty
            // whenever the prior position was non-zero. The ebook PATCH with isFinished=true already
            // reset the audio half of the shared server record, so mark the local audio row clean at
            // that same server stamp — otherwise the durable sweep sees a dirty currentTime=0 row and
            // pushes it back with isFinished=false, un-finishing the book the user just marked read.
            if (ebookStamp != null) {
                audiobookPositionStore.markSyncedAt(source.id, itemId, ebookStamp.takeIf { it > 0L } ?: now)
            }
        } else {
            // For mark-as-unread: also push currentTime=0 to the audio peer immediately, then mark
            // the audio row clean. Without this, the sweeper's reconciler falls into confirmInSync
            // (the ebook PATCH and the local save share the same timestamp, so neither ServerWon nor
            // LocalWins fires), leaving ABS with the old non-zero audio position. A subsequent
            // refreshItemProgress then re-adopts that stale position and writes it back to the DB,
            // restoring the very progress value the user just cleared.
            val audioPeer = catalog as? AudiobookProgressPeerCapability
            if (audioPeer != null) {
                val audioDuration = libraryItemDao.getById(source.id, itemId)?.audioDurationSec ?: 0.0
                val stamp = runCatching {
                    audioPeer.pushAudiobookProgress(
                        itemId = itemId,
                        currentTimeSec = 0.0,
                        durationSec = audioDuration,
                        isFinished = false,
                        lastUpdateEpochMs = now,
                    )
                }.getOrNull()
                if (stamp != null) {
                    audiobookPositionStore.markSyncedAt(source.id, itemId, stamp.takeIf { it > 0L } ?: now)
                }
            }
        }
    }

    private suspend fun activeProgressPeer(): ProgressPeerCapability? {
        val source = sourceRepository.getActive() ?: return null
        val catalog: Catalog = catalogRegistry.forSource(source) ?: return null
        return catalog as? ProgressPeerCapability
    }
}
