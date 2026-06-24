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
    /** Resolve a specific server by id (e.g. the ABS and Storyteller sides of a matched book). */
    suspend fun getById(serverId: String): Server? = null
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

    /**
     * Return the ABS-side stable user identity for this server, fetching it from `/api/me` if
     * it hasn't been persisted yet (legacy rows created before the column existed). Returns
     * null for Storyteller servers, for unknown server ids, and when the fetch fails (offline,
     * server down, invalid token). A successful fetch is persisted so subsequent calls are
     * a single DB lookup.
     */
    suspend fun ensureAbsUserId(serverId: String): String? = null
}
