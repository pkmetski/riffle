package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

sealed class AuthenticateResult {
    data class Success(val pending: PendingServer) : AuthenticateResult()
    data class WrongCredentials(val message: String = "Invalid username or password") : AuthenticateResult()
    data class NetworkError(val cause: Throwable) : AuthenticateResult()
    data class LibraryFetchFailed(val cause: Throwable) : AuthenticateResult()
    data class InsecureConnection(val type: InsecureConnectionType) : AuthenticateResult()
}

sealed class CommitServerResult {
    data class Success(val server: Server) : CommitServerResult()
    data class Failure(val cause: Throwable) : CommitServerResult()
}

interface ServerRepository {
    fun observeAll(): Flow<List<Server>>
    suspend fun getActive(): Server?
    suspend fun authenticate(
        url: ServerUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean = false,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ): AuthenticateResult
    suspend fun commit(
        pending: PendingServer,
        hiddenLibraryIds: Set<String>,
    ): CommitServerResult
    suspend fun setActive(serverId: String)
    suspend fun remove(serverId: String)
    suspend fun getServerVersion(serverId: String): String?
}
