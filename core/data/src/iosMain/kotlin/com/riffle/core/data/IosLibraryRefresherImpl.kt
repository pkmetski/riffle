package com.riffle.core.data

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
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsCoverUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.NetworkResult
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
        // Only ABS syncs items into the local mirror on iOS today. Unbounded catalogues are
        // network-only by design (ADR 0051), Local Files rows are owned by the installer, and
        // Komga is not yet enabled on iOS — none of those may be wiped by a replace-all.
        if (source.type != SourceType.ABS) return LibraryRefreshResult.Success
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
                    )
                }
                libraryItemDao.replaceAllForLibrary(source.id, libraryId, entities)
                LibraryRefreshResult.Success
            }
            is NetworkResult.Offline -> LibraryRefreshResult.NetworkError(result.cause)
            is NetworkResult.Unknown -> LibraryRefreshResult.NetworkError(result.cause)
            else -> LibraryRefreshResult.NetworkError(Exception("Request failed: $result"))
        }
    }

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        if (source.type != SourceType.ABS) return LibraryRefreshResult.Success
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
        if (source.type != SourceType.ABS) return LibraryRefreshResult.Success
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

    override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success
}
