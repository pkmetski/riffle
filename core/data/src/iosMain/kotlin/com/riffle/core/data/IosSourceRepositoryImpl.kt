package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUUID

class IosSourceRepositoryImpl(
    private val dao: SourceDao,
    private val libraryDao: LibraryDao,
    private val tokenStorage: TokenStorage,
) : SourceRepository {

    override fun observeAll(): Flow<List<Source>> =
        dao.observeAll().map { list ->
            list.map { it.toDomain() }.sortedBy { it.type.ordinal }
        }

    override suspend fun getActive(): Source? = dao.getActive()?.toDomain()

    override suspend fun getById(sourceId: String): Source? = dao.getById(sourceId)?.toDomain()

    override suspend fun commit(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult = try {
        val id = NSUUID().UUIDString
        // Save credentials before inserting the row so the first observer read already sees the token.
        tokenStorage.saveToken(id, pending.token)
        tokenStorage.savePassword(id, pending.password)
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
        CommitSourceResult.Failure(t)
    }

    override suspend fun setActive(sourceId: String) {
        dao.setActiveAtomic(sourceId)
    }

    override suspend fun remove(sourceId: String) {
        dao.deleteSourceGraph(sourceId)
        tokenStorage.deleteToken(sourceId)
    }

    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private fun SourceEntity.toDomain(): Source {
    val parsedUrl = SourceUrl.parse(url) ?: SourceUrl.parse("https://invalid.example.com")!!
    return Source(
        id = id,
        url = parsedUrl,
        isActive = isActive,
        insecureConnectionAllowed = insecureConnectionAllowed,
        username = username,
        type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.ABS),
        serverType = ServerType.fromStorageString(serverType),
        absUserId = absUserId,
    )
}
