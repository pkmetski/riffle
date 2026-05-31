package com.riffle.core.data

import com.riffle.core.domain.ProgressSyncCycleResult
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
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkSyncSessionResult
import javax.inject.Inject

class ReadingSessionRepositoryImpl @Inject constructor(
    private val api: AbsSessionApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val positionStore: ReadingPositionStore,
) : ReadingSessionRepository {

    override suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult {
        val resolved = resolveServerAndToken() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active server or token")
        )
        val (server, token) = resolved
        return when (val r = api.syncEbookProgress(server.url.value, itemId, payload.toNetwork(), token, server.insecureConnectionAllowed)) {
            is NetworkSyncSessionResult.Success -> SyncSessionResult.Success
            is NetworkSyncSessionResult.NetworkError -> SyncSessionResult.NetworkError(r.cause)
        }
    }

    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
        val resolved = resolveServerAndToken() ?: return ProgressSyncCycleResult.Offline
        val (server, token) = resolved

        val serverProgress = when (val r = api.getProgress(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkGetProgressResult.NetworkError -> return ProgressSyncCycleResult.Offline
            is NetworkGetProgressResult.Success -> r.progress
        }

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
                if (patchResult is NetworkSyncSessionResult.Success) {
                    val ts = patchResult.lastUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()
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
        val serverProgress = when (val r = api.getProgress(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkGetProgressResult.Success -> r.progress
            is NetworkGetProgressResult.NetworkError -> return
        }
        val patchResult = api.syncEbookProgress(
            server.url.value, itemId,
            NetworkEbookProgressPayload(
                ebookLocation = serverProgress.ebookLocation,
                ebookProgress = serverProgress.ebookProgress,
            ),
            token, server.insecureConnectionAllowed,
        )
        if (patchResult is NetworkSyncSessionResult.Success) {
            val ts = patchResult.lastUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()
            positionStore.updateLocalTimestamp(server.id, itemId, ts)
        }
    }

    override suspend fun setProgress(itemId: String, progress: Float) {
        val server = serverRepository.getActive() ?: return
        val cfi = positionStore.load(server.id, itemId) ?: ""
        // Bump before token check: marks the record dirty so the sync cycle pushes it
        // even if the token is missing right now.
        positionStore.updateLocalTimestamp(server.id, itemId, System.currentTimeMillis())
        val token = tokenStorage.getToken(server.id) ?: return
        api.syncEbookProgress(
            server.url.value, itemId,
            NetworkEbookProgressPayload(cfi, progress),
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
