package com.riffle.core.data

import com.riffle.core.data.credentialed.CredentialedSourceInstaller
import com.riffle.core.data.sources.SourceVersionResolver
import com.riffle.core.data.sources.SyncNamespaceResolver
import com.riffle.core.data.sources.toDomain
import com.riffle.core.database.SourceDao
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.KomgaServerInfoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SourceRepositoryImpl constructor(
    private val dao: SourceDao,
    private val tokenStorage: TokenStorage,
    private val serverInfoApi: AbsServerInfoApi,
    private val komgaServerInfoApi: KomgaServerInfoApi,
    private val filesCleaner: SourceFilesCleaner,
    // Lambda (not direct injection) breaks the SourceRepository ↔ ReadaloudSidecarStore DI
    // cycle: the sidecar store needs SourceRepository to look up per-source credentials, and the
    // repository needs the cache to purge on source removal. Lazy resolution here means the store
    // is only constructed on the removal path — long after graph resolution has settled.
    private val sidecarCache: () -> ReadaloudSidecarCache,
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
        // A Storyteller Source is a Settings-only readaloud backend (ADR 0032) — it can never be the
        // active browsable Source. Enforce the invariant here so no caller (source removal, deep
        // links, future UI) can promote one, and a stale DB row can't be re-activated.
        if (dao.getById(sourceId)?.serverType == ServerType.STORYTELLER_SERVICE.name) return
        dao.setActiveAtomic(sourceId)
    }

    override suspend fun clearActive() {
        dao.clearActiveFlag()
    }

    override suspend fun remove(sourceId: String) {
        // Keep Room cleanup atomic. Some user databases have accumulated rows in source-scoped
        // caches and cross-source readaloud tables; deleting the source row through one graph
        // cleanup avoids FK failures and leaves no partial source removal behind.
        dao.deleteSourceGraph(sourceId)
        tokenStorage.deleteToken(sourceId)
        tokenStorage.deletePassword(sourceId)
        // The file stores live outside Room, so the FK cascade above doesn't touch them — purge the
        // Source's downloaded/cached files here so they don't leak on disk after removal.
        filesCleaner.deleteAllForSource(sourceId)
        // Readaloud sidecars live in the app cacheDir and are keyed to the storyteller source id;
        // a re-added source shouldn't inherit sidecars from its predecessor.
        sidecarCache().purgeSource(sourceId)
    }

    // Both lookups are the shared commonMain algorithms iOS runs too (#1101).
    private val versionResolver = SourceVersionResolver(tokenStorage, serverInfoApi, komgaServerInfoApi)
    private val namespaceResolver = SyncNamespaceResolver(
        sourceLookup = { id -> dao.getById(id)?.toDomain() },
        tokenStorage = tokenStorage,
        remoteUserIdResolvers = remoteUserIdResolvers,
        persistRemoteId = { id, remoteId -> dao.setAbsUserId(id, remoteId) },
    )

    override suspend fun getSourceVersion(sourceId: String): String? {
        val source = dao.getById(sourceId)?.toDomain() ?: return null
        return versionResolver.resolve(source)
    }

    companion object {
        // Re-exported for legacy call sites that reference the ABS-family synthetic library id via
        // this class. New code should use [CredentialedSourceInstaller.readaloudLibraryId] directly.
        fun readaloudLibraryId(sourceId: String): String =
            CredentialedSourceInstaller.readaloudLibraryId(sourceId)
    }

    // Hop to IO explicitly: this method is called on the reader cold-open path
    // (EpubReaderViewModel.onOpenReady) from viewModelScope, whose default dispatcher is
    // Main.immediate. Without this hop, the Room read + (first-time) network resolve run on the
    // main thread and lock the UI for 500ms+ right after the reader becomes visible, making
    // swipes and scrolls feel sluggish for the first seconds of every book open.
    override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = withContext(Dispatchers.IO) {
        namespaceResolver.resolve(sourceId)
    }
}
