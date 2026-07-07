package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

sealed class AuthenticateResult {
    data class Success(val pending: PendingSource) : AuthenticateResult()
    data class WrongCredentials(val message: String = "Invalid username or password") : AuthenticateResult()
    data class NetworkError(val cause: Throwable) : AuthenticateResult()
    data class LibraryFetchFailed(val cause: Throwable) : AuthenticateResult()
    data class InsecureConnection(val type: InsecureConnectionType) : AuthenticateResult()
}

sealed class CommitSourceResult {
    data class Success(val source: Source) : CommitSourceResult()
    data class Failure(val cause: Throwable) : CommitSourceResult()
}

interface SourceRepository {
    fun observeAll(): Flow<List<Source>>
    suspend fun getActive(): Source?
    /** Resolve a specific source by id (e.g. the ABS and Storyteller sides of a matched book). */
    suspend fun getById(sourceId: String): Source? = null
    suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean = false,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ): AuthenticateResult
    suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult
    suspend fun setActive(sourceId: String)
    suspend fun remove(sourceId: String)
    suspend fun getSourceVersion(sourceId: String): String?

    /**
     * Return the ABS-side stable user identity for this source, fetching it from `/api/me` if
     * it hasn't been persisted yet (legacy rows created before the column existed). Returns
     * null for Storyteller servers, for unknown source ids, and when the fetch fails (offline,
     * server down, invalid token). A successful fetch is persisted so subsequent calls are
     * a single DB lookup.
     */
    suspend fun ensureAbsUserId(sourceId: String): String? = null
}
