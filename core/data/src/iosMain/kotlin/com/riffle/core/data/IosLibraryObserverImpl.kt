package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class IosLibraryObserverImpl(
    private val libraryDao: LibraryDao,
    private val sourceRepository: SourceRepository,
) : LibraryObserver {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLibraries(): Flow<List<Library>> =
        sourceRepository.observeAll()
            .map { sources -> sources.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()
            .flatMapLatest { sourceId ->
                if (sourceId == null) {
                    flowOf(emptyList())
                } else {
                    libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }
                }
            }

    override fun observeLibraries(sourceId: String): Flow<List<Library>> =
        libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeSeries(libraryId: String): Flow<List<com.riffle.core.models.Series>> = flowOf(emptyList())
    override fun observeCollections(libraryId: String): Flow<List<com.riffle.core.models.Collection>> = flowOf(emptyList())
    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())

    override suspend fun getItem(itemId: String): LibraryItem? = null
    override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
    override suspend fun getLibrary(libraryId: String): Library? = null
    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
}

private fun LibraryEntity.toDomain() = Library(id = id, name = name, mediaType = mediaType, isUnsupported = isUnsupported)
