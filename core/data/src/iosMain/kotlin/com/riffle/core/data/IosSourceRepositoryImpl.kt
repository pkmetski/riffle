package com.riffle.core.data

import com.riffle.core.database.SourceDao
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IosSourceRepositoryImpl(
    private val dao: SourceDao,
) : SourceRepository {

    override fun observeAll(): Flow<List<Source>> =
        dao.observeAll().map { list ->
            list.map { it.toDomain() }.sortedBy { it.type.ordinal }
        }

    override suspend fun getActive(): Source? = dao.getActive()?.toDomain()

    override suspend fun getById(sourceId: String): Source? = dao.getById(sourceId)?.toDomain()

    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        throw NotImplementedError("commit not supported on iOS MVP")

    override suspend fun setActive(sourceId: String): Unit =
        throw NotImplementedError("setActive not supported on iOS MVP")

    override suspend fun remove(sourceId: String): Unit =
        throw NotImplementedError("remove not supported on iOS MVP")

    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private fun com.riffle.core.database.SourceEntity.toDomain(): Source {
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
