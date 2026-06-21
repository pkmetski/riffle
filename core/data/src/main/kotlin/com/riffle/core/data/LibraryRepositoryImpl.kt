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
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkSeriesResult
import com.riffle.core.network.NetworkUserProgressResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val readingSessionRepository: ReadingSessionRepository,
    private val readaloudMatchingService: ReadaloudMatchingService,
    private val storytellerReadaloudSyncer: StorytellerReadaloudSyncer,
) : LibraryRepository {

    // Used to fire syncStale/reconcileLinks without blocking refreshLibraryItems.
    // coroutineScope { launch {} } waits for all children, so background work needs its own scope.
    private val backgroundScope = CoroutineScope(SupervisorJob())

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
        libraryItemDao.observeByLibraryId(libraryId).scopedToActiveServer()

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeUngroupedByLibraryId(libraryId).scopedToActiveServer()

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeInProgress(libraryId).scopedToActiveServer()

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeFinished(libraryId).scopedToActiveServer()

    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeRecentlyAdded(libraryId).scopedToActiveServer()

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> =
        libraryItemDao.observeAllBooks(libraryId).scopedToActiveServer()

    // Id of the Server whose libraries the user is currently browsing. The nav drawer only ever
    // lists the active Server's libraries, so the visible library always belongs to it.
    private val activeServerId: Flow<String?> =
        serverRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()

    // Two Servers pointing at the same Audiobookshelf instance emit identical library and item ids
    // (issue #113), so the libraryId-keyed item-shelf queries return a row per Server. Keep only the
    // active Server's copy — the one book detail reads and markAsRead/the reader write. Without this,
    // an inactive duplicate's stale progress keeps a finished book pinned to In Progress even though
    // detail shows 100%. No active Server → pass rows through (nothing is being browsed anyway), still
    // collapsing any duplicate ids. Mirrors how observeLibraries/getLibrary/getItem scope by Server.
    private fun Flow<List<LibraryItemEntity>>.scopedToActiveServer(): Flow<List<LibraryItem>> =
        combine(activeServerId) { list, serverId ->
            list.filter { serverId == null || it.serverId == serverId }
                .distinctBy { it.id }
                .map { it.toDomain() }
        }

    override fun observeSeries(libraryId: String): Flow<List<Series>> =
        seriesDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeCollections(libraryId: String): Flow<List<Collection>> =
        collectionDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
        seriesDao.observeItemsBySeriesId(seriesId).scopedToActiveServer()

    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> =
        seriesDao.observeContinueSeriesItems(libraryId).scopedToActiveServer()

    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
        collectionDao.observeItemsByCollectionId(collectionId).scopedToActiveServer()

    // Item ids are only unique within a Server (ADR 0025); reads/writes here target the active
    // Server's copy, mirroring how reading positions are keyed. No active Server → nothing to do.
    override suspend fun getItem(itemId: String): LibraryItem? {
        val serverId = serverRepository.getActive()?.id ?: return null
        return libraryItemDao.getById(serverId, itemId)?.toDomain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeItem(itemId: String): Flow<LibraryItem?> =
        serverRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { serverId ->
                if (serverId == null) flowOf(null)
                else libraryItemDao.observeById(serverId, itemId).map { it?.toDomain() }
            }

    override suspend fun getItem(serverId: String, itemId: String): LibraryItem? =
        libraryItemDao.getById(serverId, itemId)?.toDomain()

    // Library ids are only unique within a Server (issue #113); resolve against the active Server's
    // copy, mirroring how [getItem] keys item reads. No active Server → nothing to resolve.
    override suspend fun getLibrary(libraryId: String): Library? {
        val serverId = serverRepository.getActive()?.id ?: return null
        return libraryDao.getById(serverId, libraryId)?.toDomain()
    }

    override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? =
        seriesDao.findSeriesIdForItem(serverId, itemId)

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
        return coroutineScope {
            // Fire both calls simultaneously: user-progress and library items are independent
            // requests. Total latency = max(getUserProgress, getLibraryItems) instead of their sum.
            val progressDeferred = async { api.getUserProgress(server.url.value, token, server.insecureConnectionAllowed) }
            val itemsDeferred = async { api.getLibraryItems(server.url.value, libraryId, token, server.insecureConnectionAllowed) }

            val serverProgressMap = when (val r = progressDeferred.await()) {
                is NetworkUserProgressResult.Success -> r.byItemId
                is NetworkUserProgressResult.NetworkError -> emptyMap()
            }
            when (val result = itemsDeferred.await()) {
                is NetworkLibraryItemsResult.Success -> {
                    val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
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
                                coverUrl = absCoverUrl(server.url.value, item.id, item.updatedAt),
                                // For an audiobook-only item the ABS user-progress fallback already maps
                                // its listen fraction into `ebookProgress` (AbsApiClient: ebookProgress ?:
                                // progress), so this single field is the unified "how far through this
                                // item" value that surfaces audiobooks in In Progress too (ADR 0029).
                                // Note: for existing items the DAO's updateMetadata ignores this field and
                                // preserves the locally-tracked value. It is only used when inserting a
                                // new item for the first time.
                                readingProgress = serverProgress?.ebookProgress ?: item.readingProgress ?: 0f,
                                ebookFileIno = item.ebookFileIno,
                                ebookFormat = item.ebookFormat.toStorageString(),
                                hasAudio = item.hasAudio,
                                audioDurationSec = item.audioDurationSec,
                                description = item.description,
                                seriesName = item.seriesName,
                                publishedYear = item.publishedYear,
                                genres = item.genres.joinToString(","),
                                publisher = item.publisher,
                                language = item.language,
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
                    libraryDao.setUnsupported(server.id, libraryId, isUnsupported)
                    // syncStale and reconcileLinks are not on the critical path — fire them on a
                    // detached background scope so refreshLibraryItems returns immediately after
                    // the Room write. (launch {} inside coroutineScope {} would be a child and
                    // would block the scope from returning.)
                    backgroundScope.launch {
                        storytellerReadaloudSyncer.syncStale()
                        readaloudMatchingService.reconcileLinks()
                    }
                    LibraryRefreshResult.Success
                }
                is NetworkLibraryItemsResult.NetworkError -> LibraryRefreshResult.NetworkError(result.cause)
            }
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return when (val result = api.getSeries(server.url.value, libraryId, token, server.insecureConnectionAllowed)) {
            is NetworkSeriesResult.Success -> {
                val seriesEntities = result.series.map { s ->
                    SeriesEntity(
                        id = s.id,
                        libraryId = s.libraryId,
                        name = s.name,
                        coverUrl = s.items.firstOrNull()?.let { first ->
                            absCoverUrl(server.url.value, first.id, first.updatedAt)
                        },
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
        hasAudio = hasAudio,
        audioDurationSec = audioDurationSec,
        description = description,
        seriesName = seriesName,
        publishedYear = publishedYear,
        genres = genres.split(",").filter { it.isNotEmpty() },
        publisher = publisher,
        language = language,
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

    private fun absCoverUrl(baseUrl: String, itemId: String, updatedAt: Long?) =
        "$baseUrl/api/items/$itemId/cover" + (updatedAt?.let { "?t=$it" } ?: "")

    private fun CollectionEntity.toDomain() = Collection(
        id = id,
        libraryId = libraryId,
        name = name,
        bookCount = bookCount,
    )
}
