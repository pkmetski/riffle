package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Outcome of a credentialed authenticate handshake, produced by
 * [com.riffle.core.data.credentialed.CredentialedAuthenticator] implementations and consumed by
 * `AddSourceViewModel`. Kept in the domain module because [PendingSource] (which the Success case
 * carries) lives here.
 */
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
    suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult
    suspend fun setActive(sourceId: String)
    suspend fun remove(sourceId: String)
    suspend fun getSourceVersion(sourceId: String): String?

    /**
     * Resolve this source's annotation-sync namespace (#529). Delegates to
     * [WebSourceDescriptor.syncNamespaceFor]; on [SyncNamespace.PendingRemoteId] the repository
     * calls the source-kind-specific [RemoteUserIdResolver], persists the fetched id into
     * `Source.absUserId`, and re-evaluates the descriptor. Returns [SyncNamespace.LocalOnly]
     * for unknown source ids so callers can render a uniform local-only state.
     *
     * A successful cross-device resolution is persisted so subsequent calls are a single DB
     * lookup — the resolver only runs on the first open of a legacy row.
     */
    suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace =
        SyncNamespace.LocalOnly("Unknown source.")
}
