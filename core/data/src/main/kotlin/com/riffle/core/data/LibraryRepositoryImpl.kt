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
import com.riffle.core.domain.Clock
import com.riffle.core.domain.Collection
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.Series
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.errorAsThrowable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Slim Room/API wrapper that satisfies the three segregated library role interfaces. Cross-cutting
 * choreography (readaloud matcher / Storyteller catalogue sync / reading-session push) lives in
 * use-cases under `com.riffle.core.domain.usecase`, NOT here.
 */
class LibraryRepositoryImpl @Inject constructor(
    private val api: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val clock: Clock,
) : LibraryObserver, LibraryMutator, LibraryRefresher {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLibraries(): Flow<List<Library>> =
        serverRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { serverId ->
                if (serverId == null) flowOf(emptyList())
                else libraryDao.observeBySourceId(serverId).map { list -> list.map { it.toDomain() } }
            }

    override fun observeLibraries(serverId: String): Flow<List<Library>> =
        libraryDao.observeBySourceId(serverId).map { list -> list.map { it.toDomain() } }

    // Id of the Server whose libraries the user is currently browsing. The nav drawer only ever
    // lists the active Server's libraries, so the visible library always belongs to it.
    private val activeServerId: Flow<String?> =
        serverRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()

    // Library-scoped item flows resolve the active Server's id and pass it as the DAO's primary
    // scope. library_items is keyed by (serverId, id) (ADR 0025), so the query itself enforces
    // server isolation — no post-query filter required. With no active Server the screen has
    // nothing to show, so we emit an empty list rather than mixing data across Servers.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scopedItemFlow(
        query: (String) -> Flow<List<LibraryItemEntity>>,
    ): Flow<List<LibraryItem>> =
        activeServerId.flatMapLatest { serverId ->
            if (serverId == null) flowOf(emptyList())
            else query(serverId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeByLibraryId(serverId, libraryId) }

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeUngroupedByLibraryId(serverId, libraryId) }

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeInProgress(serverId, libraryId) }

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeFinished(serverId, libraryId) }

    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeRecentlyAdded(serverId, libraryId) }

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> libraryItemDao.observeAllBooks(serverId, libraryId) }

    override fun observeSeries(libraryId: String): Flow<List<Series>> =
        seriesDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeCollections(libraryId: String): Flow<List<Collection>> =
        collectionDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> seriesDao.observeItemsBySeriesId(serverId, seriesId) }

    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> seriesDao.observeContinueSeriesItems(serverId, libraryId) }

    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { serverId -> collectionDao.observeItemsByCollectionId(serverId, collectionId) }

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
        libraryItemDao.updateLastOpenedAt(serverId, itemId, clock.nowMs())
    }

    override suspend fun updateReadingProgress(itemId: String, progress: Float) {
        val serverId = serverRepository.getActive()?.id ?: return
        libraryItemDao.updateReadingProgress(serverId, itemId, progress)
    }

    override suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float) {
        libraryItemDao.updateReadingProgress(serverId, itemId, progress)
    }

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = api.getLibraries(server.url.value, token, server.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return LibraryRefreshResult.NetworkError(result.errorAsThrowable())
        val entities = result.value
            .filter { it.mediaType == "book" }
            .map { LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, serverId = server.id) }
        libraryDao.replaceAllForSource(server.id, entities)
        return LibraryRefreshResult.Success
    }

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        return coroutineScope {
            // Fire both calls simultaneously: user-progress and library items are independent
            // requests. Total latency = max(getUserProgress, getLibraryItems) instead of their sum.
            val progressDeferred = async { api.getUserProgress(server.url.value, token, server.insecureConnectionAllowed) }
            val itemsDeferred = async { api.getLibraryItems(server.url.value, libraryId, token, server.insecureConnectionAllowed) }

            val serverProgressMap = (progressDeferred.await() as? NetworkResult.Success)?.value ?: emptyMap()
            val result = itemsDeferred.await()
            if (result !is NetworkResult.Success) return@coroutineScope LibraryRefreshResult.NetworkError(result.errorAsThrowable())
            val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(server.id, libraryId).associate { it.id to it.lastOpenedAt }
            val entities = result.value
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
                        finishedAt = serverProgress?.finishedAt,
                    )
                }
            libraryItemDao.replaceAllForLibrary(server.id, libraryId, entities)
            val isUnsupported = entities.isNotEmpty() && entities.none { it.ebookFormat != EbookFormat.Unsupported.toStorageString() }
            libraryDao.setUnsupported(server.id, libraryId, isUnsupported)
            LibraryRefreshResult.Success
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = api.getSeries(server.url.value, libraryId, token, server.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return LibraryRefreshResult.NetworkError(result.errorAsThrowable())
        val seriesEntities = result.value.map { s ->
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
        val seriesItemEntities = result.value.flatMap { s ->
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
        return LibraryRefreshResult.Success
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        val server = serverRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(server.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = api.getCollections(server.url.value, libraryId, token, server.insecureConnectionAllowed)
        if (result !is NetworkResult.Success) return LibraryRefreshResult.NetworkError(result.errorAsThrowable())
        val collectionEntities = result.value.map { c ->
            CollectionEntity(
                id = c.id,
                libraryId = c.libraryId,
                name = c.name,
                bookCount = c.bookCount,
            )
        }
        val collectionItemEntities = result.value.flatMap { c ->
            c.items.map { item ->
                CollectionItemEntity(collectionId = c.id, serverId = server.id, itemId = item.id)
            }
        }
        collectionDao.replaceAllForLibrary(libraryId, collectionEntities, collectionItemEntities)
        return LibraryRefreshResult.Success
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
