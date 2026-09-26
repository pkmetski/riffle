package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.SeriesCapability
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
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsCoverUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.sync.DirtyProgressLedger
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

class IosLibraryRefresherImpl(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val absLibraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val komgaLibraryApi: KomgaLibraryApi,
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
    private val libraryItemDao: LibraryItemDao,
    private val catalogRegistry: CatalogRegistry,
    private val dirtyProgressLedger: DirtyProgressLedger,
) : LibraryRefresher {

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        return when (source.type) {
            SourceType.ABS -> {
                val token = tokenStorage.getToken(source.id)
                    ?: return LibraryRefreshResult.NoActiveServer
                val result = absLibraryApi.getLibraries(
                    baseUrl = source.url.value,
                    token = token,
                    insecureAllowed = source.insecureConnectionAllowed,
                )
                when (result) {
                    is NetworkResult.Success -> {
                        val entities = result.value
                            .filter { it.mediaType == "book" }
                            .map { LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, sourceId = source.id) }
                        libraryDao.replaceAllForSource(source.id, entities)
                        LibraryRefreshResult.Success
                    }
                    is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
                    is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
                    else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
                }
            }
            SourceType.KOMGA -> {
                val token = tokenStorage.getToken(source.id)
                    ?: return LibraryRefreshResult.NoActiveServer
                val result = komgaLibraryApi.getLibraries(
                    baseUrl = source.url.value,
                    token = token,
                    insecureAllowed = source.insecureConnectionAllowed,
                )
                when (result) {
                    is NetworkResult.Success -> {
                        val entities = result.value
                            .map { LibraryEntity(id = it.id, name = it.name, mediaType = "book", sourceId = source.id) }
                        libraryDao.replaceAllForSource(source.id, entities)
                        LibraryRefreshResult.Success
                    }
                    is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
                    is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
                    else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
                }
            }
            SourceType.CHITANKA -> {
                val entities = listOf(
                    LibraryEntity(id = "books", name = "Chitanka", mediaType = "book", sourceId = source.id),
                    LibraryEntity(id = "audiobooks", name = "gramofonche", mediaType = "audiobook", sourceId = source.id),
                )
                libraryDao.replaceAllForSource(source.id, entities)
                LibraryRefreshResult.Success
            }
            SourceType.GUTENBERG -> {
                val entities = listOf(
                    LibraryEntity(id = "books", name = "Books", mediaType = "book", sourceId = source.id),
                )
                libraryDao.replaceAllForSource(source.id, entities)
                LibraryRefreshResult.Success
            }
            SourceType.RADIO_ES -> {
                val entities = listOf(
                    LibraryEntity(id = "podcasts", name = "Podcasts", mediaType = "audiobook", sourceId = source.id),
                    LibraryEntity(id = "stations", name = "Radio", mediaType = "audiobook", sourceId = source.id),
                )
                libraryDao.replaceAllForSource(source.id, entities)
                LibraryRefreshResult.Success
            }
            else -> LibraryRefreshResult.Success
        }
    }

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        // Unbounded catalogues (Chitanka, Gutenberg, RadioES) are network-only per ADR 0051
        // and must not be wiped by a replace-all. ABS and Komga both use the local mirror.
        if (source.type.isUnboundedCatalog) return LibraryRefreshResult.Success
        if (source.type == SourceType.ABS) {
            return refreshAbsLibraryItems(source, libraryId)
        }
        // For Komga (and any future catalogued source): browse via the CatalogRegistry.
        // If no factory is registered (e.g. LOCAL_FILES has no catalog), no-op — rows
        // for those sources are inserted by other paths (scanner, etc.).
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.Success
        val items = try {
            catalog.browse(libraryId, pageSize = Int.MAX_VALUE)
        } catch (t: Throwable) {
            return LibraryRefreshResult.NetworkError(t)
        }
        val lastOpenedAtById = libraryItemDao.getLastOpenedAtMap(source.id, libraryId)
            .associate { it.id to it.lastOpenedAt }
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val entities = items.map { item ->
            LibraryItemEntity(
                sourceId = source.id,
                id = item.id,
                libraryId = item.rootId,
                title = item.title,
                author = item.author,
                coverUrl = item.coverUrl ?: "",
                readingProgress = item.readingProgress ?: 0f,
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
                lastOpenedAt = lastOpenedAtById[item.id],
                addedAt = item.addedAt ?: nowMs,
                isbn = item.isbn,
                asin = item.asin,
            )
        }
        libraryItemDao.replaceAllForLibrary(source.id, libraryId, entities)
        return LibraryRefreshResult.Success
    }

    private suspend fun refreshAbsLibraryItems(source: com.riffle.core.models.Source, libraryId: String): LibraryRefreshResult {
        val token = tokenStorage.getToken(source.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = absLibraryApi.getLibraryItems(
            baseUrl = source.url.value,
            libraryId = libraryId,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        return when (result) {
            is NetworkResult.Success -> {
                val lastOpenedAtById = libraryItemDao.getLastOpenedAtMap(source.id, libraryId)
                    .associate { it.id to it.lastOpenedAt }
                val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val entities = result.value.map { item ->
                    LibraryItemEntity(
                        sourceId = source.id,
                        id = item.id,
                        libraryId = item.libraryId,
                        title = item.title,
                        author = item.author,
                        coverUrl = AbsCoverUrl.of(source.url.value, item.id, item.updatedAt),
                        // Initial seed for newly inserted rows only — replaceAllForLibrary's
                        // updateMetadata path preserves the locally tracked value for existing rows.
                        readingProgress = item.readingProgress ?: 0f,
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
                        lastOpenedAt = lastOpenedAtById[item.id],
                        addedAt = item.addedAt ?: nowMs,
                        isbn = item.isbn,
                        asin = item.asin,
                        progressServerUpdatedAt = item.progressUpdatedAt ?: 0L,
                    )
                }
                libraryItemDao.replaceAllForLibrary(source.id, libraryId, entities)
                // replaceAllForLibrary's updateMetadata intentionally preserves readingProgress on
                // existing rows so a pending local edit is not overwritten. Mirror the just-fetched
                // server value for all clean rows so a book advanced on another device appears
                // correctly in Continue Reading without requiring the user to open it first.
                val dirtyIds = (dirtyProgressLedger.dirtyEbookItems(source.id) +
                    dirtyProgressLedger.dirtyAudioItems(source.id)).toSet()
                for (item in result.value) {
                    if (item.id in dirtyIds) continue
                    val progress = item.readingProgress ?: continue
                    // Last-update-wins: the library-list endpoint lags the per-item endpoint, so
                    // only adopt when its stamp is not older than what we stored — otherwise it
                    // overwrites a fresher per-item/detail value and the bars disagree.
                    libraryItemDao.updateReadingProgressFromServer(
                        source.id, item.id, progress, item.progressUpdatedAt ?: 0L,
                    )
                }
                LibraryRefreshResult.Success
            }
            is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
            is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
            else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        if (source.type == SourceType.ABS) {
            return refreshAbsSeries(source, libraryId)
        }
        // For Komga (and any future source with SeriesCapability): delegate to the catalog.
        if (source.type.isUnboundedCatalog) return LibraryRefreshResult.Success
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.Success
        val seriesCap = catalog as? SeriesCapability ?: return LibraryRefreshResult.Success
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

    private suspend fun refreshAbsSeries(source: com.riffle.core.models.Source, libraryId: String): LibraryRefreshResult {
        val token = tokenStorage.getToken(source.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = absLibraryApi.getSeries(
            baseUrl = source.url.value,
            libraryId = libraryId,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        return when (result) {
            is NetworkResult.Success -> {
                val seriesEntities = result.value.map { s ->
                    SeriesEntity(
                        id = s.id,
                        libraryId = s.libraryId,
                        name = s.name,
                        coverUrl = s.items.firstOrNull()?.let { AbsCoverUrl.of(source.url.value, it.id, it.updatedAt) },
                        bookCount = s.bookCount,
                    )
                }
                val maxNumericBySeriesId = result.value.associate { s ->
                    s.id to (s.items.mapNotNull { it.sequence?.toFloatOrNull() }.maxOrNull() ?: 0f)
                }
                val seriesItemEntities = result.value.flatMap { s ->
                    val maxNumeric = maxNumericBySeriesId[s.id] ?: 0f
                    s.items.mapIndexed { index, entry ->
                        SeriesItemEntity(
                            seriesId = s.id,
                            sourceId = source.id,
                            itemId = entry.id,
                            sequenceOrder = entry.sequence?.toFloatOrNull()
                                ?: (maxNumeric + 1f + index.toFloat()),
                        )
                    }
                }
                seriesDao.replaceAllForLibrary(libraryId, seriesEntities, seriesItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
            is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
            else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
        }
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        if (source.type == SourceType.ABS) {
            return refreshAbsCollections(source, libraryId)
        }
        // For Komga (and any future source with CollectionsCapability): delegate to the catalog.
        if (source.type.isUnboundedCatalog) return LibraryRefreshResult.Success
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.Success
        val collectionsCap = catalog as? CollectionsCapability ?: return LibraryRefreshResult.Success
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

    private suspend fun refreshAbsCollections(source: com.riffle.core.models.Source, libraryId: String): LibraryRefreshResult {
        val token = tokenStorage.getToken(source.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = absLibraryApi.getCollections(
            baseUrl = source.url.value,
            libraryId = libraryId,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        return when (result) {
            is NetworkResult.Success -> {
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
                        CollectionItemEntity(
                            collectionId = c.id,
                            sourceId = source.id,
                            itemId = item.id,
                        )
                    }
                }
                collectionDao.replaceAllForLibrary(libraryId, collectionEntities, collectionItemEntities)
                LibraryRefreshResult.Success
            }
            is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
            is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
            else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
        }
    }

    override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        if (source.id != sourceId) return LibraryRefreshResult.NoActiveServer
        val catalog = catalogRegistry.forSource(source) ?: return LibraryRefreshResult.NoActiveServer
        if (source.type.isUnboundedCatalog) return LibraryRefreshResult.Success
        val progressPeer = catalog as? ProgressPeerCapability ?: return LibraryRefreshResult.Success
        val dirty = (dirtyProgressLedger.dirtyEbookItems(source.id) +
            dirtyProgressLedger.dirtyAudioItems(source.id)).toSet()
        if (itemId in dirty) return LibraryRefreshResult.Success
        val sp = try {
            progressPeer.pullProgress(itemId)
        } catch (t: Throwable) {
            return LibraryRefreshResult.NetworkError(t)
        } ?: return LibraryRefreshResult.Success
        val fraction = sp.unifiedLibraryFraction() ?: return LibraryRefreshResult.Success
        val finishedAt = sp.finishedAt ?: sp.lastUpdate.takeIf { sp.isFinished }
        // Last-update-wins so a lagging library-list bulk value can't overwrite this fresher
        // per-item value (library-vs-detail bar disagreement).
        libraryItemDao.updateReadingProgressFromServer(sourceId, itemId, fraction, sp.lastUpdate)
        libraryItemDao.updateFinishedAt(sourceId, itemId, finishedAt)
        return LibraryRefreshResult.Success
    }

    private fun com.riffle.core.catalog.BookFormat.toEbookFormat(): EbookFormat = when (this) {
        com.riffle.core.catalog.BookFormat.Epub -> EbookFormat.Epub
        com.riffle.core.catalog.BookFormat.Pdf -> EbookFormat.Pdf
        com.riffle.core.catalog.BookFormat.Cbz -> EbookFormat.Cbz
        com.riffle.core.catalog.BookFormat.Audiobook -> EbookFormat.Unsupported
        com.riffle.core.catalog.BookFormat.Unsupported -> EbookFormat.Unsupported
    }
}
