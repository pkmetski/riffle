package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.KomgaLibraryApi
import com.riffle.core.network.NetworkResult

class IosLibraryRefresherImpl(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val absLibraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
    private val komgaLibraryApi: KomgaLibraryApi,
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

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success
}
