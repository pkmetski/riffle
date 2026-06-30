package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.ServerRepository
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
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val positionStore: ReadingPositionStore,
    private val audiobookPositionStore: AudiobookPositionStore,
    private val readaloudResumeStore: ReadaloudResumeStore,
    private val libraryItemDao: LibraryItemDao,
) : ReadingSessionRepository {

    override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
        val resolved = resolveServerAndToken() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active server or token")
        )
        val (server, token) = resolved
        val r = api.syncEbookProgress(server.url.value, itemId, payload.toNetwork(), token, server.insecureConnectionAllowed)
        return if (r is NetworkResult.Success) SyncSessionResult.Success else SyncSessionResult.NetworkError(r.errorAsThrowable())
    }

    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
        val resolved = resolveServerAndToken() ?: return ProgressSyncCycleResult.Offline
        val (server, token) = resolved

        val getRes = api.getProgress(server.url.value, itemId, token, server.insecureConnectionAllowed)
        if (getRes !is NetworkResult.Success) return ProgressSyncCycleResult.Offline
        val serverProgress = getRes.value

        val localUpdatedAt = positionStore.loadLocalUpdatedAt(server.id, itemId)

        return when {
            serverProgress.lastUpdate > localUpdatedAt -> {
                positionStore.updateLocalTimestamp(server.id, itemId, serverProgress.lastUpdate)
                ProgressSyncCycleResult.ServerWins(
                    ServerProgress(
                        ebookLocation = serverProgress.ebookLocation,
                        ebookProgress = serverProgress.ebookProgress,
                        lastUpdate = serverProgress.lastUpdate,
                    )
                )
            }
            localUpdatedAt > serverProgress.lastUpdate -> {
                val patchResult = api.syncEbookProgress(server.url.value, itemId, payload.toNetwork(), token, server.insecureConnectionAllowed)
                if (patchResult is NetworkResult.Success) {
                    val ts = patchResult.value.takeIf { it > 0L } ?: System.currentTimeMillis()
                    positionStore.updateLocalTimestamp(server.id, itemId, ts)
                }
                ProgressSyncCycleResult.LocalWins
            }
            else -> ProgressSyncCycleResult.InSync
        }
    }

    override suspend fun touchOpenTimestamp(itemId: String) {
        val resolved = resolveServerAndToken() ?: return
        val (server, token) = resolved
        val getRes = api.getProgress(server.url.value, itemId, token, server.insecureConnectionAllowed)
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
            server.url.value, itemId,
            NetworkEbookProgressPayload(
                ebookLocation = serverProgress.ebookLocation,
                ebookProgress = serverProgress.ebookProgress,
            ),
            token, server.insecureConnectionAllowed,
        )
    }

    override suspend fun markFinished(itemId: String, finished: Boolean) {
        val server = serverRepository.getActive() ?: return
        // Read = keep the saved page; unread = clear it so the reader reopens at the start and the
        // 0 progress isn't contradicted by a stale cfi.
        val ebookLocation = if (finished) (positionStore.load(server.id, itemId) ?: "") else ""
        val now = System.currentTimeMillis()
        if (!finished) {
            // Wipe EVERY local position store that could otherwise restore a stale position the next
            // time the book is opened (and re-save it, resurrecting the progress). The ebook reading
            // position alone isn't enough: a matched/readaloud book also resumes from the audiobook
            // position (translated back into an ebook locator on open) and the readaloud-resume row.
            positionStore.save(server.id, itemId, "")
            audiobookPositionStore.save(server.id, itemId, 0.0)
            audiobookPositionStore.updateLocalTimestamp(server.id, itemId, now)
            readaloudResumeStore.clear(server.id, itemId)
        }
        // Bump before token check: marks the record dirty so the sync cycle pushes it
        // even if the token is missing right now.
        positionStore.updateLocalTimestamp(server.id, itemId, now)
        libraryItemDao.updateFinishedAt(server.id, itemId, if (finished) now else null)
        val token = tokenStorage.getToken(server.id) ?: return
        api.syncEbookProgress(
            server.url.value, itemId,
            // isFinished resets the audio half of the record too: true→progress 1, false→currentTime
            // and progress 0. Both halves move together so neither can re-shadow the other.
            NetworkEbookProgressPayload(
                ebookLocation = ebookLocation,
                ebookProgress = if (finished) 1.0f else 0.0f,
                isFinished = finished,
            ),
            token, server.insecureConnectionAllowed,
        )
        // PATCH failure intentionally ignored — next sync cycle will push
    }

    private suspend fun resolveServerAndToken(): Pair<Server, String>? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return server to token
    }

    private fun SessionPayload.toNetwork() = NetworkEbookProgressPayload(
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
    )
}
