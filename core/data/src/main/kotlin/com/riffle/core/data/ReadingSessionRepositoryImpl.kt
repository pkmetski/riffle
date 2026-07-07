package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.Source
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SyncSessionResult
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.errorAsThrowable
import javax.inject.Inject

class ReadingSessionRepositoryImpl @Inject constructor(
    private val api: AbsSessionApi,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val positionStore: ReadingPositionStore,
    private val audiobookPositionStore: AudiobookPositionStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val libraryItemDao: LibraryItemDao,
    private val clock: Clock,
) : ReadingSessionRepository {

    override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
        val resolved = resolveSourceAndToken() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active server or token")
        )
        val (source, token) = resolved
        val r = api.syncEbookProgress(source.url.value, itemId, payload.toNetwork(), token, source.insecureConnectionAllowed)
        return if (r is NetworkResult.Success) SyncSessionResult.Success else SyncSessionResult.NetworkError(r.errorAsThrowable())
    }

    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
        val resolved = resolveSourceAndToken() ?: return ProgressSyncCycleResult.Offline
        val (source, token) = resolved

        val getRes = api.getProgress(source.url.value, itemId, token, source.insecureConnectionAllowed)
        if (getRes !is NetworkResult.Success) return ProgressSyncCycleResult.Offline
        val serverProgress = getRes.value

        val localUpdatedAt = positionStore.loadLocalUpdatedAt(source.id, itemId)

        return when {
            serverProgress.lastUpdate > localUpdatedAt -> {
                positionStore.updateLocalTimestamp(source.id, itemId, serverProgress.lastUpdate)
                ProgressSyncCycleResult.ServerWins(
                    ServerProgress(
                        ebookLocation = serverProgress.ebookLocation,
                        ebookProgress = serverProgress.ebookProgress,
                        lastUpdate = serverProgress.lastUpdate,
                    )
                )
            }
            localUpdatedAt > serverProgress.lastUpdate -> {
                val patchResult = api.syncEbookProgress(source.url.value, itemId, payload.toNetwork(), token, source.insecureConnectionAllowed)
                if (patchResult is NetworkResult.Success) {
                    val ts = patchResult.value.takeIf { it > 0L } ?: clock.nowMs()
                    positionStore.updateLocalTimestamp(source.id, itemId, ts)
                }
                ProgressSyncCycleResult.LocalWins
            }
            else -> ProgressSyncCycleResult.InSync
        }
    }

    override suspend fun touchOpenTimestamp(itemId: String) {
        val resolved = resolveSourceAndToken() ?: return
        val (source, token) = resolved
        val getRes = api.getProgress(source.url.value, itemId, token, source.insecureConnectionAllowed)
        if (getRes !is NetworkResult.Success) return
        val serverProgress = getRes.value
        // Deliberately do NOT bump positionStore.localUpdatedAt here. Matching the server's
        // post-PATCH lastUpdate without also writing the server's cfi locally would create a
        // "local in sync but cfi empty" state: the next runSyncCycle would see equal stamps
        // and return InSync, the reader would open at page 1, and the next local save would
        // PATCH first-page state over the real server position. Leaving local untouched lets
        // the first runSyncCycle after this call see server > local and trigger ServerWins,
        // restoring the saved position to the navigator.
        api.syncEbookProgress(
            source.url.value, itemId,
            NetworkEbookProgressPayload(
                ebookLocation = serverProgress.ebookLocation,
                ebookProgress = serverProgress.ebookProgress,
            ),
            token, source.insecureConnectionAllowed,
        )
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
        // Bump before token check: marks the record dirty so the sync cycle pushes it
        // even if the token is missing right now.
        positionStore.updateLocalTimestamp(source.id, itemId, now)
        libraryItemDao.updateFinishedAt(source.id, itemId, if (finished) now else null)
        val token = tokenStorage.getToken(source.id) ?: return
        api.syncEbookProgress(
            source.url.value, itemId,
            // isFinished resets the audio half of the record too: true→progress 1, false→currentTime
            // and progress 0. Both halves move together so neither can re-shadow the other.
            NetworkEbookProgressPayload(
                ebookLocation = ebookLocation,
                ebookProgress = if (finished) 1.0f else 0.0f,
                isFinished = finished,
            ),
            token, source.insecureConnectionAllowed,
        )
        // PATCH failure intentionally ignored — next sync cycle will push
    }

    private suspend fun resolveSourceAndToken(): Pair<Source, String>? {
        val source = sourceRepository.getActive() ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        return source to token
    }

    private fun SessionPayload.toNetwork() = NetworkEbookProgressPayload(
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
    )
}
