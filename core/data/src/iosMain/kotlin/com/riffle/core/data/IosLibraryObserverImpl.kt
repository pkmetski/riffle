package com.riffle.core.data

import com.riffle.core.database.CollectionDao
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class IosLibraryObserverImpl(
    private val libraryDao: LibraryDao,
    private val sourceRepository: SourceRepository,
    private val libraryItemDao: LibraryItemDao,
    private val seriesDao: SeriesDao,
    private val collectionDao: CollectionDao,
) : LibraryObserver {

    /** Emits the active source id, re-emitting when the active source changes. */
    private fun activeSourceId(): Flow<String?> =
        sourceRepository.observeAll()
            .map { sources -> sources.firstOrNull { it.isActive }?.id }
            .distinctUntilChanged()

    private fun observeItems(
        query: (sourceId: String) -> Flow<List<LibraryItemEntity>>,
    ): Flow<List<LibraryItem>> =
        activeSourceId().flatMapLatest { sourceId ->
            if (sourceId == null) {
                flowOf(emptyList())
            } else {
                query(sourceId).map { list -> list.map { it.toDomainLibraryItem() } }
            }
        }

    override fun observeLibraries(): Flow<List<Library>> =
        activeSourceId().flatMapLatest { sourceId ->
            if (sourceId == null) {
                flowOf(emptyList())
            } else {
                libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }
            }
        }

    override fun observeLibraries(sourceId: String): Flow<List<Library>> =
        libraryDao.observeBySourceId(sourceId).map { list -> list.map { it.toDomain() } }

    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeByLibraryId(sourceId, libraryId) }

    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeUngroupedByLibraryId(sourceId, libraryId) }

    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeInProgress(sourceId, libraryId) }

    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeFinished(sourceId, libraryId) }

    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeRecentlyAdded(sourceId, libraryId) }

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> libraryItemDao.observeAllBooks(sourceId, libraryId) }

    override fun observeSeries(libraryId: String): Flow<List<com.riffle.core.models.Series>> =
        seriesDao.observeByLibraryId(libraryId).map { list ->
            list.map { com.riffle.core.models.Series(id = it.id, libraryId = it.libraryId, name = it.name, coverUrl = it.coverUrl, bookCount = it.bookCount) }
        }

    override fun observeCollections(libraryId: String): Flow<List<com.riffle.core.models.Collection>> =
        collectionDao.observeByLibraryId(libraryId).map { list ->
            list.map { com.riffle.core.models.Collection(id = it.id, libraryId = it.libraryId, name = it.name, bookCount = it.bookCount) }
        }

    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> seriesDao.observeItemsBySeriesId(sourceId, seriesId) }

    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> seriesDao.observeContinueSeriesItems(sourceId, libraryId) }

    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> =
        observeItems { sourceId -> collectionDao.observeItemsByCollectionId(sourceId, collectionId) }

    override suspend fun getItem(itemId: String): LibraryItem? {
        val sourceId = activeSourceId().firstOrNull() ?: return null
        return libraryItemDao.getById(sourceId, itemId)?.toDomainLibraryItem()
    }

    override fun observeItem(itemId: String): Flow<LibraryItem?> =
        activeSourceId().flatMapLatest { sourceId ->
            if (sourceId == null) {
                flowOf(null)
            } else {
                libraryItemDao.observeById(sourceId, itemId).map { it?.toDomainLibraryItem() }
            }
        }

    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? =
        libraryItemDao.getById(sourceId, itemId)?.toDomainLibraryItem()

    override suspend fun getLibrary(libraryId: String): Library? {
        val sourceId = activeSourceId().firstOrNull() ?: return null
        return libraryDao.observeBySourceId(sourceId).firstOrNull()
            ?.firstOrNull { it.id == libraryId }
            ?.toDomain()
    }

    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
}

private fun LibraryEntity.toDomain() = Library(id = id, name = name, mediaType = mediaType, isUnsupported = isUnsupported)
