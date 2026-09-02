package com.riffle.feature.library

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySectionItemsTest {

    private fun item(id: String) = LibraryItem(
        id = id,
        libraryId = "lib1",
        title = "Title",
        author = "",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private inner class FakeObserver(
        private val inProgress: List<LibraryItem> = emptyList(),
        private val finished: List<LibraryItem> = emptyList(),
        private val recentlyAdded: List<LibraryItem> = emptyList(),
        private val continueSeries: List<LibraryItem> = emptyList(),
    ) : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(inProgress)
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(finished)
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(recentlyAdded)
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(continueSeries)
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
    }

    @Test
    fun inProgressSectionRoutesToObserveInProgressItems() = runTest {
        val items = listOf(item("a"), item("b"))
        val observer = FakeObserver(inProgress = items)
        val result = librarySectionItems(observer, "lib1", LibrarySectionType.IN_PROGRESS).first()
        assertEquals(items, result)
    }

    @Test
    fun finishedSectionRoutesToObserveFinishedItems() = runTest {
        val items = listOf(item("c"))
        val observer = FakeObserver(finished = items)
        val result = librarySectionItems(observer, "lib1", LibrarySectionType.FINISHED).first()
        assertEquals(items, result)
    }

    @Test
    fun recentlyAddedSectionRoutesToObserveRecentlyAddedItems() = runTest {
        val items = listOf(item("d"), item("e"))
        val observer = FakeObserver(recentlyAdded = items)
        val result = librarySectionItems(observer, "lib1", LibrarySectionType.RECENTLY_ADDED).first()
        assertEquals(items, result)
    }

    @Test
    fun continueSeriesSectionRoutesToObserveContinueSeriesItems() = runTest {
        val items = listOf(item("f"))
        val observer = FakeObserver(continueSeries = items)
        val result = librarySectionItems(observer, "lib1", LibrarySectionType.CONTINUE_SERIES).first()
        assertEquals(items, result)
    }

    @Test
    fun eachSectionTypeReceivesOnlyItsOwnItems() = runTest {
        val observer = FakeObserver(
            inProgress = listOf(item("ip")),
            finished = listOf(item("fin")),
            recentlyAdded = listOf(item("ra")),
            continueSeries = listOf(item("cs")),
        )
        assertEquals(listOf(item("ip")), librarySectionItems(observer, "lib1", LibrarySectionType.IN_PROGRESS).first())
        assertEquals(listOf(item("fin")), librarySectionItems(observer, "lib1", LibrarySectionType.FINISHED).first())
        assertEquals(listOf(item("ra")), librarySectionItems(observer, "lib1", LibrarySectionType.RECENTLY_ADDED).first())
        assertEquals(listOf(item("cs")), librarySectionItems(observer, "lib1", LibrarySectionType.CONTINUE_SERIES).first())
    }
}
