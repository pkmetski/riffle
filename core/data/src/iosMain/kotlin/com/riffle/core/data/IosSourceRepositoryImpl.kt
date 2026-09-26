package com.riffle.core.data

import com.riffle.core.data.sources.SourceVersionResolver
import com.riffle.core.data.sources.SyncNamespaceResolver
import com.riffle.core.data.sources.toDomain
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.KomgaServerInfoApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import platform.Foundation.NSUUID

class IosSourceRepositoryImpl(
    private val dao: SourceDao,
    private val libraryDao: LibraryDao,
    private val tokenStorage: TokenStorage,
    absServerInfoApi: AbsServerInfoApi,
    komgaServerInfoApi: KomgaServerInfoApi,
    remoteUserIdResolvers: Map<SourceType, RemoteUserIdResolver>,
    private val dispatchers: DispatcherProvider,
) : SourceRepository {

    // The same commonMain lookups Android's SourceRepositoryImpl runs (#1101): the version used
    // to be a hard-coded null here, and ensureSyncNamespace fell through to the interface default
    // (LocalOnly for everything), which starved the annotation sweep of anything to push.
    private val versionResolver = SourceVersionResolver(tokenStorage, absServerInfoApi, komgaServerInfoApi)
    private val namespaceResolver = SyncNamespaceResolver(
        sourceLookup = { id -> dao.getById(id)?.toDomain() },
        tokenStorage = tokenStorage,
        remoteUserIdResolvers = remoteUserIdResolvers,
        persistRemoteId = { id, remoteId -> dao.setAbsUserId(id, remoteId) },
    )

    override fun observeAll(): Flow<List<Source>> =
        dao.observeAll().map { list ->
            list.map { it.toDomain() }.sortedBy { it.type.ordinal }
        }

    override suspend fun getActive(): Source? = dao.getActive()?.toDomain()

    override suspend fun getById(sourceId: String): Source? = dao.getById(sourceId)?.toDomain()

    override suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult {
        val id = NSUUID().UUIDString
        // Save credentials before inserting the row so the first observer read already sees the token.
        tokenStorage.saveToken(id, pending.token)
        tokenStorage.savePassword(id, pending.password)
        return try {
            val entity = SourceEntity(
                id = id,
                url = pending.url.value,
                isActive = false,
                insecureConnectionAllowed = pending.insecureConnectionAllowed,
                username = pending.username,
                serverType = pending.serverType.name,
                absUserId = pending.userId.takeIf { it.isNotBlank() },
                type = pending.sourceType.name,
            )
            val inserted = dao.upsertAsFirstIfNoActive(entity)
            val libraryRows = pending.libraries.map {
                LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, sourceId = id)
            }
            libraryDao.replaceAllForSource(sourceId = id, libraries = libraryRows)
            CommitSourceResult.Success(inserted.toDomain())
        } catch (t: Throwable) {
            tokenStorage.deleteToken(id)
            tokenStorage.deletePassword(id)
            CommitSourceResult.Failure(t)
        }
    }

    override suspend fun setActive(sourceId: String) {
        dao.setActiveAtomic(sourceId)
    }

    override suspend fun clearActive() {
        dao.clearActiveFlag()
    }

    override suspend fun remove(sourceId: String) {
        dao.deleteSourceGraph(sourceId)
        tokenStorage.deleteToken(sourceId)
        tokenStorage.deletePassword(sourceId)
    }

    override suspend fun getSourceVersion(sourceId: String): String? {
        val source = dao.getById(sourceId)?.toDomain() ?: return null
        return versionResolver.resolve(source)
    }

    // Off Main for the same reason as Android: this runs on the reader cold-open path, and the
    // first call for a legacy row includes a network round-trip to fetch the remote user id.
    override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = withContext(dispatchers.io) {
        namespaceResolver.resolve(sourceId)
    }
}
