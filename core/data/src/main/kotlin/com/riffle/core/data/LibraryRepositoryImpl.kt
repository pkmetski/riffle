package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItemsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : LibraryRepository {

    override fun observeLibraries(): Flow<List<Library>> =
        serverRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { serverId ->
                if (serverId == null) flowOf(emptyList())
                else libraryDao.observeByServerId(serverId).map { list -> list.map { it.toDomain() } }
            }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getLibraries(server.url.value, token, server.insecureConnectionAllowed)) {
            is NetworkLibrariesResult.Success -> {
                val entities = result.libraries
                    .filter { it.mediaType == "book" }
                    .map { LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, serverId = server.id) }
                libraryDao.deleteByServerId(server.id)
                libraryDao.upsertAll(entities)
                LibraryRefreshResult.Success
            }
            is NetworkLibrariesResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getLibraryItems(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkLibraryItemsResult.Success -> {
                val entities = result.items
                    .sortedByDescending { it.isSupported }
                    .distinctBy { it.title.trim().lowercase() }
                    .map { item ->
                        LibraryItemEntity(
                            id = item.id,
                            libraryId = item.libraryId,
                            title = item.title,
                            author = item.author,
                            coverUrl = "${server.url.value}/api/items/${item.id}/cover",
                            readingProgress = item.readingProgress,
                            isDownloaded = false,
                            isSupported = item.isSupported,
                        )
                    }
                libraryItemDao.deleteByLibraryId(libraryId)
                libraryItemDao.upsertAll(entities)
                val isUnsupported = entities.isNotEmpty() && entities.none { it.isSupported }
                libraryDao.setUnsupported(libraryId, isUnsupported)
                LibraryRefreshResult.Success
            }
            is NetworkLibraryItemsResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    private fun LibraryEntity.toDomain() = Library(id = id, name = name, mediaType = mediaType, isUnsupported = isUnsupported)

    private fun LibraryItemEntity.toDomain() = LibraryItem(
        id = id,
        libraryId = libraryId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        readingProgress = readingProgress,
        isCached = false,
        isDownloaded = isDownloaded,
        isSupported = isSupported,
    )
}
