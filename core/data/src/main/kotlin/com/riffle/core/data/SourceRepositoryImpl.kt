package com.riffle.core.data

import com.riffle.core.data.credentialed.CredentialedSourceInstaller
import com.riffle.core.data.credentialed.toDomain
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.SourceDao
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import com.riffle.core.models.ServerType
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.network.AbsServerInfoApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SourceRepositoryImpl @Inject constructor(
    private val dao: SourceDao,
    private val tokenStorage: TokenStorage,
    private val serverInfoApi: AbsServerInfoApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val filesCleaner: SourceFilesCleaner,
    private val installer: CredentialedSourceInstaller,
    private val remoteUserIdResolvers: Map<SourceType, @JvmSuppressWildcards RemoteUserIdResolver>,
) : SourceRepository {

    // Sort by SourceType so consumers (Settings sources list, drawer source switcher) render in
    // the same canonical order: ABS servers → LocalFiles → Chitanka. Kotlin's sortedBy is stable,
    // so the DAO's alphabetical (username, url) ordering is preserved as the tiebreaker within
    // each bucket.
    override fun observeAll(): Flow<List<Source>> =
        dao.observeAll().map { list ->
            list.map { it.toDomain() }.sortedBy { it.type.ordinal }
        }

    override suspend fun getActive(): Source? = dao.getActive()?.toDomain()

    override suspend fun getById(sourceId: String): Source? = dao.getById(sourceId)?.toDomain()

    override suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult = installer.install(pending, hiddenLibraryIds)

    override suspend fun setActive(sourceId: String) {
        // A Storyteller Source is a Settings-only readaloud backend (ADR 0026) — it can never be the
        // active browsable Source. Enforce the invariant here so no caller (source removal, deep
        // links, future UI) can promote one, and a stale DB row can't be re-activated.
        if (dao.getById(sourceId)?.serverType == ServerType.STORYTELLER_SERVICE.name) return
        dao.setActiveAtomic(sourceId)
    }

    override suspend fun remove(sourceId: String) {
        // Cascade: clear per-library items + the libraries themselves + the token + the source row.
        // For Storyteller sources this purges the synthetic Readaloud library and its books;
        // for ABS sources it cleans up real libraries and their items. ReadaloudLinks cross-source
        // cleanup belongs to #36 (matching slice) — until that lands the count of links is 0.
        libraryDao.libraryIdsForSource(sourceId).forEach { libraryItemDao.deleteByLibraryId(sourceId, it) }
        libraryDao.deleteBySourceId(sourceId)
        dao.deleteById(sourceId)
        tokenStorage.deleteToken(sourceId)
        tokenStorage.deletePassword(sourceId)
        // The file stores live outside Room, so the FK cascade above doesn't touch them — purge the
        // Source's downloaded/cached files here so they don't leak on disk after removal.
        filesCleaner.deleteAllForSource(sourceId)
    }

    override suspend fun getSourceVersion(sourceId: String): String? {
        val source = dao.getById(sourceId)?.toDomain() ?: return null
        // Storyteller exposes no /server-info endpoint; the UI deliberately shows no version for it.
        if (source.serverType == ServerType.STORYTELLER_SERVICE) return null
        val token = tokenStorage.getToken(sourceId) ?: return null
        return serverInfoApi.getServerInfo(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
    }

    companion object {
        // Re-exported for legacy call sites that reference the ABS-family synthetic library id via
        // this class. New code should use [CredentialedSourceInstaller.readaloudLibraryId] directly.
        fun readaloudLibraryId(sourceId: String): String =
            CredentialedSourceInstaller.readaloudLibraryId(sourceId)
    }

    override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace {
        val source = dao.getById(sourceId)?.toDomain()
            ?: return SyncNamespace.LocalOnly("Unknown source.")
        val descriptor = WebSourceDescriptors.forType(source.type)
            ?: return SyncNamespace.LocalOnly("No descriptor for source type ${source.type}.")
        val initial = descriptor.syncNamespaceFor(source)
        if (initial !is SyncNamespace.PendingRemoteId) return initial

        // Descriptor advertises cross-device identity but the remote user id hasn't been fetched
        // yet — dispatch to the per-SourceType resolver. Anonymous / local descriptors never
        // reach this branch (they return LocalOnly above), so a missing resolver here means a
        // sync-eligible source kind wasn't wired into RemoteUserIdResolverModule.
        val resolver = remoteUserIdResolvers[source.type]
            ?: return SyncNamespace.LocalOnly("No remote-id resolver registered for ${source.type}.")
        val token = tokenStorage.getToken(sourceId)
            ?: return SyncNamespace.PendingRemoteId
        val fetched = resolver.resolve(source, token)?.takeIf { it.isNotBlank() }
            ?: return SyncNamespace.PendingRemoteId
        dao.setAbsUserId(sourceId, fetched)
        // Project the freshly-fetched id through the descriptor's dedicated hook instead of
        // synthesising a `source.copy(absUserId = fetched)` and re-invoking syncNamespaceFor —
        // avoids the double-eval and keeps the "how do I turn an id into a namespace" logic in
        // one method per descriptor.
        return descriptor.namespaceFromRemoteId(source, fetched)
    }
}
