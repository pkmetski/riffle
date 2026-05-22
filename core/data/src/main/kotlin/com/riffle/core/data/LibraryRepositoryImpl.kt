package com.riffle.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.Collection
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkSeriesResult
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
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : LibraryRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
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

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeUngroupedByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeInProgress(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeFinished(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeAllBooks(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeSeries(libraryId: String): Flow<List<Series>> =
        seriesDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeCollections(libraryId: String): Flow<List<Collection>> =
        collectionDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
        seriesDao.observeItemsBySeriesId(seriesId).map { list -> list.map { it.toDomain() } }

    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
        collectionDao.observeItemsByCollectionId(collectionId).map { list -> list.map { it.toDomain() } }

    override suspend fun getItem(itemId: String): LibraryItem? =
        libraryItemDao.getById(itemId)?.toDomain()

    override suspend fun markItemOpened(itemId: String) {
        libraryItemDao.updateLastOpenedAt(itemId, System.currentTimeMillis())
    }

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
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
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
                            ebookFileIno = item.ebookFileIno,
                            ebookFormat = item.ebookFormat.toStorageString(),
                            description = item.description,
                            seriesName = item.seriesName,
                            publishedYear = item.publishedYear,
                            genres = item.genres.joinToString(","),
                            publisher = item.publisher,
                            lastOpenedAt = lastOpenedAtMap[item.id],
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

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getSeries(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkSeriesResult.Success -> {
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
                val seriesEntities = result.series.map { s ->
                    SeriesEntity(
                        id = s.id,
                        libraryId = s.libraryId,
                        name = s.name,
                        coverUrl = s.items.firstOrNull()?.let { "${server.url.value}/api/items/${it.id}/cover" },
                        bookCount = s.bookCount,
                    )
                }
                val itemEntities = result.series.flatMap { s ->
                    s.items.map { item ->
                        LibraryItemEntity(
                            id = item.id,
                            libraryId = item.libraryId,
                            title = item.title,
                            author = item.author,
                            coverUrl = "${server.url.value}/api/items/${item.id}/cover",
                            readingProgress = item.readingProgress,
                            isDownloaded = false,
                            isSupported = item.isSupported,
                            ebookFileIno = item.ebookFileIno,
                            ebookFormat = item.ebookFormat.toStorageString(),
                            description = item.description,
                            seriesName = item.seriesName,
                            publishedYear = item.publishedYear,
                            genres = item.genres.joinToString(","),
                            publisher = item.publisher,
                            lastOpenedAt = lastOpenedAtMap[item.id],
                        )
                    }
                }.distinctBy { it.id }
                val seriesItemEntities = result.series.flatMap { s ->
                    s.items.mapIndexed { index, item ->
                        SeriesItemEntity(
                            seriesId = s.id,
                            itemId = item.id,
                            sequenceOrder = item.sequence?.toFloatOrNull() ?: (index + 1).toFloat(),
                        )
                    }
                }
                seriesDao.deleteItemsByLibraryId(libraryId)
                seriesDao.deleteByLibraryId(libraryId)
                libraryItemDao.upsertAll(itemEntities)
                seriesDao.upsertAll(seriesEntities)
                seriesDao.upsertAllItems(seriesItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkSeriesResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getCollections(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkCollectionResult.Success -> {
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
                val collectionEntities = result.collections.map { c ->
                    CollectionEntity(
                        id = c.id,
                        libraryId = c.libraryId,
                        name = c.name,
                        bookCount = c.bookCount,
                    )
                }
                val itemEntities = result.collections.flatMap { c ->
                    c.items.map { item ->
                        LibraryItemEntity(
                            id = item.id,
                            libraryId = item.libraryId,
                            title = item.title,
                            author = item.author,
                            coverUrl = "${server.url.value}/api/items/${item.id}/cover",
                            readingProgress = item.readingProgress,
                            isDownloaded = false,
                            isSupported = item.isSupported,
                            ebookFileIno = item.ebookFileIno,
                            ebookFormat = item.ebookFormat.toStorageString(),
                            description = item.description,
                            seriesName = item.seriesName,
                            publishedYear = item.publishedYear,
                            genres = item.genres.joinToString(","),
                            publisher = item.publisher,
                            lastOpenedAt = lastOpenedAtMap[item.id],
                        )
                    }
                }.distinctBy { it.id }
                val collectionItemEntities = result.collections.flatMap { c ->
                    c.items.map { item ->
                        CollectionItemEntity(collectionId = c.id, itemId = item.id)
                    }
                }
                collectionDao.deleteItemsByLibraryId(libraryId)
                collectionDao.deleteByLibraryId(libraryId)
                libraryItemDao.upsertAll(itemEntities)
                collectionDao.upsertAll(collectionEntities)
                collectionDao.upsertAllItems(collectionItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkCollectionResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
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
        ebookFormat = EbookFormat.from(ebookFormat.takeIf { isSupported }),
        ebookFileIno = ebookFileIno,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = genres.split(",").filter { it.isNotEmpty() },
        publisher = publisher,
        lastOpenedAt = lastOpenedAt,
    )

    private fun SeriesEntity.toDomain() = Series(
        id = id,
        libraryId = libraryId,
        name = name,
        coverUrl = coverUrl,
        bookCount = bookCount,
    )

    private fun CollectionEntity.toDomain() = Collection(
        id = id,
        libraryId = libraryId,
        name = name,
        bookCount = bookCount,
    )
}
