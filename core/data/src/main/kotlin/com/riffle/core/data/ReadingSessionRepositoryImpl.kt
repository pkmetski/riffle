package com.riffle.core.data

import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SyncSessionResult
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkSyncSessionResult
import javax.inject.Inject

class ReadingSessionRepositoryImpl @Inject constructor(
    private val api: AbsSessionApi,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
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
