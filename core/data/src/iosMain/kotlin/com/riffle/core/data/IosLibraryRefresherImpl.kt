package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult

class IosLibraryRefresherImpl(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val absLibraryApi: AbsLibraryApi,
    private val libraryDao: LibraryDao,
) : LibraryRefresher {

    override suspend fun refreshLibraries(): LibraryRefreshResult {
        val source = sourceRepository.getActive() ?: return LibraryRefreshResult.NoActiveServer
        val token = tokenStorage.getToken(source.id) ?: return LibraryRefreshResult.NoActiveServer
        val result = absLibraryApi.getLibraries(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        return when (result) {
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

    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success

    override suspend fun refreshItemProgress(sourceId: String, itemId: String): LibraryRefreshResult =
        LibraryRefreshResult.Success
}
