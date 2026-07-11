package com.riffle.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
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
import com.riffle.core.domain.SourceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Slim Room/Catalog wrapper that satisfies the three segregated library role interfaces.
 * Cross-cutting choreography (readaloud matcher / Storyteller catalogue sync / reading-session
 * push) lives in use-cases under `com.riffle.core.domain.usecase`, NOT here.
 */
class LibraryRepositoryImpl @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val libraryDao: LibraryDao,
    private val libraryItemDao: LibraryItemDao,
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
    private val sourceRepository: SourceRepository,
    private val clock: Clock,
) : LibraryObserver, LibraryMutator, LibraryRefresher {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLibraries(): Flow<List<Library>> =
        sourceRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { sourceId ->
                if (sourceId == null) flowOf(emptyList())
                else libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }
            }

    override fun observeLibraries(sourceId: String): Flow<List<Library>> =
        libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }

    // Id of the Source whose libraries the user is currently browsing. The nav drawer only ever
    // lists the active Source's libraries, so the visible library always belongs to it.
    private val activeServerId: Flow<String?> =
        sourceRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()

    // Library-scoped item flows resolve the active Source's id and pass it as the DAO's primary
    // scope. library_items is keyed by (sourceId, id) (ADR 0025), so the query itself enforces
    // source isolation — no post-query filter required. With no active Source the screen has
    // nothing to show, so we emit an empty list rather than mixing data across Sources.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scopedItemFlow(
        query: (String) -> Flow<List<LibraryItemEntity>>,
    ): Flow<List<LibraryItem>> =
        activeServerId.flatMapLatest { sourceId ->
            if (sourceId == null) flowOf(emptyList())
            else query(sourceId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeByLibraryId(sourceId, libraryId) }

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeUngroupedByLibraryId(sourceId, libraryId) }

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeInProgress(sourceId, libraryId) }

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeFinished(sourceId, libraryId) }

    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeRecentlyAdded(sourceId, libraryId) }

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> libraryItemDao.observeAllBooks(sourceId, libraryId) }

    override fun observeSeries(libraryId: String): Flow<List<Series>> =
        seriesDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeCollections(libraryId: String): Flow<List<Collection>> =
        collectionDao.observeByLibraryId(libraryId).map { list -> list.map { it.toDomain() } }

    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> seriesDao.observeItemsBySeriesId(sourceId, seriesId) }

    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> seriesDao.observeContinueSeriesItems(sourceId, libraryId) }

    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
        scopedItemFlow { sourceId -> collectionDao.observeItemsByCollectionId(sourceId, collectionId) }

    // Item ids are only unique within a Source (ADR 0025); reads/writes here target the active
    // Source's copy, mirroring how reading positions are keyed. No active Source → nothing to do.
    override suspend fun getItem(itemId: String): LibraryItem? {
        val sourceId = sourceRepository.getActive()?.id ?: return null
        return libraryItemDao.getById(sourceId, itemId)?.toDomain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeItem(itemId: String): Flow<LibraryItem?> =
        sourceRepository.observeAll()
            .map { servers -> servers.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { sourceId ->
                if (sourceId == null) flowOf(null)
                else libraryItemDao.observeById(sourceId, itemId).map { it?.toDomain() }
            }

    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? =
        libraryItemDao.getById(sourceId, itemId)?.toDomain()

    // Library ids are only unique within a Source (issue #113); resolve against the active Source's
    // copy, mirroring how [getItem] keys item reads. No active Source → nothing to resolve.
    override suspend fun getLibrary(libraryId: String): Library? {
        val sourceId = sourceRepository.getActive()?.id ?: return null
        return libraryDao.getById(sourceId, libraryId)?.toDomain()
    }

    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? =
        seriesDao.findSeriesIdForItem(sourceId, itemId)

    override suspend fun markItemOpened(itemId: String) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        libraryItemDao.updateLastOpenedAt(sourceId, itemId, clock.nowMs())
    }

    override suspend fun updateReadingProgress(itemId: String, progress: Float) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        libraryItemDao.updateReadingProgress(sourceId, itemId, progress)
    }

    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
        libraryItemDao.updateReadingProgress(sourceId, itemId, progress)
    }

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.NoActiveServer
        val roots = try {
            catalog.listRoots()
        } catch (t: Throwable) {
            return LibraryRefreshResult.NetworkError(t)
        }
        val entities = roots
            .filter { it.mediaType == "book" }
            .map { LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, sourceId = source.id) }
        libraryDao.replaceAllForSource(source.id, entities)
        return LibraryRefreshResult.Success
    }

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.NoActiveServer
        // Unbounded remote catalogues (Chitanka; future OPDS/Gutenberg) do not populate a Room
        // mirror of library_items — ADR 0042 explicitly names them as network-only. If refresh
        // fires against one of these (e.g. LibraryItemsViewModel reached from a non-drawer route),
        // no-op the refresh instead of scraping /new into Room and letting the ABS-shaped grid
        // render it as if the item were owned by the local catalogue.
        if (source.type == com.riffle.core.domain.SourceType.CHITANKA) {
            return LibraryRefreshResult.Success
        }
        val progressPeer = catalog as? ProgressPeerCapability
        return coroutineScope {
            // Fire both calls simultaneously: user-progress and library items are independent
            // requests. Total latency = max(pullAllProgress, browse) instead of their sum.
            val progressDeferred = async {
                try {
                    progressPeer?.pullAllProgress().orEmpty()
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            val itemsDeferred = async {
                // Ask for one un-paged sweep of the library — refresh replaces the whole set.
                runCatching { catalog.browse(libraryId, pageSize = Int.MAX_VALUE) }
            }

            val serverProgressMap = progressDeferred.await().associateBy { it.itemId }
            val itemsResult = itemsDeferred.await()
            val items = itemsResult.getOrElse { return@coroutineScope LibraryRefreshResult.NetworkError(it) }
            val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(source.id, libraryId).associate { it.id to it.lastOpenedAt }
            val entities = items
                .sortedByDescending { it.ebookFormat != com.riffle.core.catalog.BookFormat.Unsupported }
                .distinctBy { it.title.trim().lowercase() }
                .map { item ->
                    val serverProgress = serverProgressMap[item.id]
                    LibraryItemEntity(
                        sourceId = source.id,
                        id = item.id,
                        libraryId = item.rootId,
                        title = item.title,
                        author = item.author,
                        coverUrl = item.coverUrl ?: "",
                        // For an audiobook-only item the ABS user-progress fallback already maps
                        // its listen fraction into `ebookProgress` (AbsApiClient: ebookProgress ?:
                        // progress), so this single field is the unified "how far through this
                        // item" value that surfaces audiobooks in In Progress too (ADR 0029).
                        // Note: for existing items the DAO's updateMetadata ignores this field and
                        // preserves the locally-tracked value. It is only used when inserting a
                        // new item for the first time.
                        readingProgress = serverProgress?.ebookProgress ?: item.readingProgress ?: 0f,
                        ebookFileIno = item.ebookFileIno,
                        ebookFormat = item.ebookFormat.toEbookFormat().toStorageString(),
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
                        // Fall back to "now" for catalogs that don't supply an addedAt (some
                        // Sources genuinely have no server-side timestamp) so the row still
                        // participates in Recently Added ordering.
                        addedAt = item.addedAt ?: clock.nowMs(),
                        isbn = item.isbn,
                        asin = item.asin,
                        finishedAt = serverProgress?.finishedAt,
                    )
                }
            libraryItemDao.replaceAllForLibrary(source.id, libraryId, entities)
            val isUnsupported = entities.isNotEmpty() && entities.none { it.ebookFormat != EbookFormat.Unsupported.toStorageString() }
            libraryDao.setUnsupported(source.id, libraryId, isUnsupported)
            LibraryRefreshResult.Success
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.NoActiveServer
        val seriesCap = catalog as? com.riffle.core.catalog.SeriesCapability ?: return LibraryRefreshResult.Success
        val series = try {
            seriesCap.listSeries(libraryId)
        } catch (t: Throwable) {
            return LibraryRefreshResult.NetworkError(t)
        }
        val seriesEntities = series.map { s ->
            SeriesEntity(
                id = s.id,
                libraryId = s.rootId,
                name = s.name,
                coverUrl = s.coverUrl,
                bookCount = s.bookCount,
            )
        }
        val seriesItemEntities = series.flatMap { s ->
            // Nulls have to sort AFTER every numeric entry within the same series — `series_items`
            // is ORDER BY sequenceOrder ASC in SeriesDao, so a plain (index+1) fallback lets an
            // unnumbered book land between "2" and "10". Shift nulls past the max numeric so they
            // trail (preserving their input order, which the Catalog already delivers per
            // SeriesEntryOrdering).
            val maxNumeric = s.items.mapNotNull { it.sequence?.toFloatOrNull() }.maxOrNull() ?: 0f
            s.items.mapIndexed { index, entry ->
                SeriesItemEntity(
                    seriesId = s.id,
                    sourceId = source.id,
                    itemId = entry.itemId,
                    sequenceOrder = entry.sequence?.toFloatOrNull()
                        ?: (maxNumeric + 1f + index.toFloat()),
                )
            }
        }
        seriesDao.replaceAllForLibrary(libraryId, seriesEntities, seriesItemEntities)
        return LibraryRefreshResult.Success
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.NoActiveServer
        val collectionsCap = catalog as? com.riffle.core.catalog.CollectionsCapability ?: return LibraryRefreshResult.Success
        val collections = try {
            collectionsCap.listCollections(libraryId)
        } catch (t: Throwable) {
            return LibraryRefreshResult.NetworkError(t)
        }
        val collectionEntities = collections.map { c ->
            CollectionEntity(
                id = c.id,
                libraryId = c.rootId,
                name = c.name,
                bookCount = c.bookCount,
            )
        }
        val collectionItemEntities = collections.flatMap { c ->
            c.itemIds.map { itemId ->
                CollectionItemEntity(collectionId = c.id, sourceId = source.id, itemId = itemId)
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
        sourceId = sourceId,
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
        pageCount = pageCount,
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

    private fun com.riffle.core.catalog.BookFormat.toEbookFormat(): EbookFormat = when (this) {
        com.riffle.core.catalog.BookFormat.Epub -> EbookFormat.Epub
        com.riffle.core.catalog.BookFormat.Pdf -> EbookFormat.Pdf
        com.riffle.core.catalog.BookFormat.Cbz -> EbookFormat.Cbz
        com.riffle.core.catalog.BookFormat.Audiobook -> EbookFormat.Unsupported
        com.riffle.core.catalog.BookFormat.Unsupported -> EbookFormat.Unsupported
    }

}
