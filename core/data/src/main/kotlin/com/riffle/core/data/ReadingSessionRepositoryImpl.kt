package com.riffle.core.data

import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
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
        val (baseUrl, token) = resolveCredentials() ?: return SyncSessionResult.NetworkError(
            IllegalStateException("No active server or token")
        )
        return when (val r = api.syncEbookProgress(baseUrl, itemId, payload.toNetwork(), token, insecureAllowed())) {
            is NetworkSyncSessionResult.Success -> SyncSessionResult.Success
            is NetworkSyncSessionResult.NetworkError -> SyncSessionResult.NetworkError(r.cause)
        }
    }

    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult {
        val (baseUrl, token) = resolveCredentials() ?: return ProgressSyncCycleResult.Offline
        val insecure = insecureAllowed()

        val serverProgress = when (val r = api.getProgress(baseUrl, itemId, token, insecure)) {
            is NetworkGetProgressResult.NetworkError -> return ProgressSyncCycleResult.Offline
            is NetworkGetProgressResult.Success -> r.progress
        }

        val localUpdatedAt = positionStore.loadLocalUpdatedAt(itemId)

        return when {
            serverProgress.lastUpdate > localUpdatedAt -> {
                positionStore.updateLocalTimestamp(itemId, serverProgress.lastUpdate)
                ProgressSyncCycleResult.ServerWins(ServerProgress(serverProgress.ebookLocation, serverProgress.lastUpdate))
            }
            localUpdatedAt > serverProgress.lastUpdate -> {
                val patchResult = api.syncEbookProgress(baseUrl, itemId, payload.toNetwork(), token, insecure)
                if (patchResult is NetworkSyncSessionResult.Success) {
                    val ts = patchResult.lastUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()
                    positionStore.updateLocalTimestamp(itemId, ts)
                }
                ProgressSyncCycleResult.LocalWins
            }
            else -> ProgressSyncCycleResult.InSync
        }
    }

    override suspend fun setProgress(itemId: String, progress: Float) {
        val cfi = positionStore.load(itemId) ?: ""
        // Bump before credential check: marks the record dirty so the sync cycle pushes it
        // even if we have no server right now (offline / no active server).
        positionStore.updateLocalTimestamp(itemId, System.currentTimeMillis())
        val (baseUrl, token) = resolveCredentials() ?: return
        api.syncEbookProgress(
            baseUrl, itemId,
            NetworkEbookProgressPayload(cfi, progress),
            token, insecureAllowed(),
        )
        // PATCH failure intentionally ignored — next sync cycle will push
    }

    private suspend fun resolveCredentials(): Pair<String, String>? {
        val server = serverRepository.getActive() ?: return null
        val token = tokenStorage.getToken(server.id) ?: return null
        return server.url.value to token
    }

    private suspend fun insecureAllowed(): Boolean =
        serverRepository.getActive()?.insecureConnectionAllowed ?: false

    private fun SessionPayload.toNetwork() = NetworkEbookProgressPayload(
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
    )
}
