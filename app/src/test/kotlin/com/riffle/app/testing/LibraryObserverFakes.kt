package com.riffle.app.testing

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Collection
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal [LibraryObserver] stub for tests that need to control the [observeAllBooks] stream.
 * All other methods return empty flows so callers that don't care about them compile without
 * additional setup.
 */
class FakeLibraryObserver(
    private val allBooksFlow: Flow<List<LibraryItem>> = flowOf(emptyList()),
    private val serverSourceItemsFlow: Flow<List<LibraryItem>> = flowOf(emptyList()),
) : LibraryObserver {
    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = allBooksFlow
    override fun observeAllItemsForSource(sourceId: String): Flow<List<LibraryItem>> = serverSourceItemsFlow
    override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
    override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
    override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
    override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override suspend fun getItem(itemId: String): LibraryItem? = null
    override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
    override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
    override suspend fun getLibrary(libraryId: String): Library? = null
    override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
}
