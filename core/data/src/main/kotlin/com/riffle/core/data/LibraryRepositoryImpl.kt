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
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkSeriesResult
import com.riffle.core.network.NetworkStorytellerBooksResult
import com.riffle.core.network.NetworkUserProgressResult
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val storytellerApi: StorytellerLibraryApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val readingSessionRepository: ReadingSessionRepository,
    private val readaloudMatchingService: ReadaloudMatchingService,
    private val storytellerReadaloudSyncer: StorytellerReadaloudSyncer,
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

    override fun observeLibraries(serverId: String): Flow<List<Library>> =
        libraryDao.observeByServerId(serverId).map { list -> list.map { it.toDomain() } }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeUngroupedByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeInProgress(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeFinished(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeRecentlyAdded(libraryId).map { list -> list.map { it.toDomain() } }

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

    // Item ids are only unique within a Server (ADR 0025); reads/writes here target the active
    // Server's copy, mirroring how reading positions are keyed. No active Server → nothing to do.
    override suspend fun getItem(itemId: String): LibraryItem? {
        val serverId = serverRepository.getActive()?.id ?: return null
        return libraryItemDao.getById(serverId, itemId)?.toDomain()
    }

    override suspend fun getItem(serverId: String, itemId: String): LibraryItem? =
        libraryItemDao.getById(serverId, itemId)?.toDomain()

    override suspend fun getLibrary(libraryId: String): Library? =
        libraryDao.getById(libraryId)?.toDomain()

    override suspend fun markItemOpened(itemId: String) {
        val serverId = serverRepository.getActive()?.id ?: return
        libraryItemDao.updateLastOpenedAt(serverId, itemId, System.currentTimeMillis())
        // Best-effort push so other devices see this open via mediaProgress.lastUpdate. Offline
        // failures are intentionally swallowed — the local stamp lifts the server timestamp via
        // max() on the next successful library refresh.
        readingSessionRepository.touchOpenTimestamp(itemId)
    }

    override suspend fun updateReadingProgress(itemId: String, progress: Float) {
        val serverId = serverRepository.getActive()?.id ?: return
        libraryItemDao.updateReadingProgress(serverId, itemId, progress)
    }

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getLibraries(server.url.value, token, server.insecureConnectionAllowed)) {
            is NetworkLibrariesResult.Success -> {
                val entities = result.libraries
                    .filter { it.mediaType == "book" }
                    .map { LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, serverId = server.id) }
                libraryDao.replaceAllForServer(server.id, entities)
                LibraryRefreshResult.Success
            }
            is NetworkLibrariesResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        if (server.serverType == ServerType.STORYTELLER) {
            return refreshStorytellerReadalouds(server, token, libraryId)
        }
        val serverProgressMap = when (val r = api.getUserProgress(server.url.value, token, server.insecureConnectionAllowed)) {
            is NetworkUserProgressResult.Success -> r.byItemId
            is NetworkUserProgressResult.NetworkError -> emptyMap()
        }
        return when (val result = api.getLibraryItems(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkLibraryItemsResult.Success -> {
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
                val localProgressMap = libraryItemDao.getReadingProgressMap(libraryId).associate { it.id to it.readingProgress }
                val entities = result.items
                    .sortedByDescending { it.isSupported }
                    .distinctBy { it.title.trim().lowercase() }
                    .map { item ->
                        val serverProgress = serverProgressMap[item.id]
                        LibraryItemEntity(
                            serverId = server.id,
                            id = item.id,
                            libraryId = item.libraryId,
                            title = item.title,
                            author = item.author,
                            coverUrl = "${server.url.value}/api/items/${item.id}/cover",
                            readingProgress = serverProgress?.ebookProgress ?: item.readingProgress ?: localProgressMap[item.id] ?: 0f,
                            ebookFileIno = item.ebookFileIno,
                            ebookFormat = item.ebookFormat.toStorageString(),
                            description = item.description,
                            seriesName = item.seriesName,
                            publishedYear = item.publishedYear,
                            genres = item.genres.joinToString(","),
                            publisher = item.publisher,
                            // Surface the most recent read activity across devices: pick whichever
                            // of (local stamp, server's mediaProgress.lastUpdate) is later. Either
                            // can lead — the local stamp wins between syncs on this device, the
                            // server stamp wins once another device has read more recently.
                            lastOpenedAt = mergeLastOpenedAt(lastOpenedAtMap[item.id], serverProgress?.lastUpdate),
                            addedAt = item.addedAt,
                            isbn = item.isbn,
                            asin = item.asin,
                        )
                    }
                libraryItemDao.replaceAllForLibrary(libraryId, entities)
                val isUnsupported = entities.isNotEmpty() && entities.none { it.ebookFormat != EbookFormat.Unsupported.toStorageString() }
                libraryDao.setUnsupported(libraryId, isUnsupported)
                storytellerReadaloudSyncer.syncStale()
                readaloudMatchingService.reconcileLinks()
                LibraryRefreshResult.Success
            }
            is NetworkLibraryItemsResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        // Storyteller exposes no Series endpoint (ADR 0020); the tab is hidden for Storyteller
        // libraries, but if this is called we no-op rather than try an unsupported call.
        if (server.serverType == ServerType.STORYTELLER) return LibraryRefreshResult.Success
        return when (val result = api.getSeries(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkSeriesResult.Success -> {
                val seriesEntities = result.series.map { s ->
                    SeriesEntity(
                        id = s.id,
                        libraryId = s.libraryId,
                        name = s.name,
                        coverUrl = s.items.firstOrNull()?.let { "${server.url.value}/api/items/${it.id}/cover" },
                        bookCount = s.bookCount,
                    )
                }
                val seriesItemEntities = result.series.flatMap { s ->
                    s.items.mapIndexed { index, item ->
                        SeriesItemEntity(
                            seriesId = s.id,
                            serverId = server.id,
                            itemId = item.id,
                            sequenceOrder = item.sequence?.toFloatOrNull() ?: (index + 1).toFloat(),
                        )
                    }
                }
                seriesDao.replaceAllForLibrary(libraryId, seriesEntities, seriesItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkSeriesResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        // Storyteller exposes no Collections endpoint (ADR 0020); see refreshSeries for parity.
        if (server.serverType == ServerType.STORYTELLER) return LibraryRefreshResult.Success
        return when (val result = api.getCollections(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkCollectionResult.Success -> {
                val collectionEntities = result.collections.map { c ->
                    CollectionEntity(
                        id = c.id,
                        libraryId = c.libraryId,
                        name = c.name,
                        bookCount = c.bookCount,
                    )
                }
                val collectionItemEntities = result.collections.flatMap { c ->
                    c.items.map { item ->
                        CollectionItemEntity(collectionId = c.id, serverId = server.id, itemId = item.id)
                    }
                }
                collectionDao.replaceAllForLibrary(libraryId, collectionEntities, collectionItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkCollectionResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
        }
    }

    private suspend fun refreshStorytellerReadalouds(
        server: com.riffle.core.domain.Server,
        token: String,
        libraryId: String,
    ): LibraryRefreshResult = when (
        val result = storytellerApi.listReadalouds(server.url.value, token, server.insecureConnectionAllowed)
    ) {
        is NetworkStorytellerBooksResult.Success -> {
            val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
            val localProgressMap = libraryItemDao.getReadingProgressMap(libraryId).associate { it.id to it.readingProgress }
            val entities = storytellerBooksToEntities(
                books = result.books,
                serverId = server.id,
                libraryId = libraryId,
                coverUrlOf = { bookId -> storytellerApi.coverUrl(server.url.value, bookId) },
                lastOpenedAtMap = lastOpenedAtMap,
                progressMap = localProgressMap,
            )
            libraryItemDao.replaceAllForLibrary(libraryId, entities)
            libraryDao.setUnsupported(libraryId, false)
            readaloudMatchingService.reconcileLinks()
            LibraryRefreshResult.Success
        }
        is NetworkStorytellerBooksResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
    }

    private fun mergeLastOpenedAt(local: Long?, server: Long?): Long? {
        val a = local ?: 0L
        val b = server ?: 0L
        val merged = maxOf(a, b)
        return merged.takeIf { it > 0L }
    }

    private fun LibraryEntity.toDomain() = Library(id = id, name = name, mediaType = mediaType, isUnsupported = isUnsupported)

    private fun LibraryItemEntity.toDomain() = LibraryItem(
        id = id,
        serverId = serverId,
        libraryId = libraryId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        readingProgress = readingProgress,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.from(ebookFormat),
        ebookFileIno = ebookFileIno,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = genres.split(",").filter { it.isNotEmpty() },
        publisher = publisher,
        lastOpenedAt = lastOpenedAt,
        addedAt = addedAt,
        isbn = isbn,
        asin = asin,
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
