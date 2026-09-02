package com.riffle.app.feature.library

import com.riffle.core.domain.LibraryObserver
import com.riffle.feature.library.librarySectionItems
import com.riffle.feature.library.LibrarySectionType
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionViewModelTest {

    @Test
    fun `recently added section observes recently added items by library id`() = runTest {
        val expected = listOf(item("recent-1"))
        val observer = SectionObserver(
            recentlyAdded = expected,
            inProgress = listOf(item("wrong-in-progress")),
            finished = listOf(item("wrong-finished")),
        )

        val result = librarySectionItems(
            libraryObserver = observer,
            libraryId = "books",
            sectionType = LibrarySectionType.RECENTLY_ADDED,
        ).first()

        assertEquals(expected, result)
        assertEquals(listOf("books"), observer.recentlyAddedLibraryIds)
        assertEquals(emptyList<String>(), observer.inProgressLibraryIds)
        assertEquals(emptyList<String>(), observer.finishedLibraryIds)
    }

    @Test
    fun `finished section observes finished items by library id`() = runTest {
        val expected = listOf(item("finished-1"))
        val observer = SectionObserver(finished = expected)

        val result = librarySectionItems(
            libraryObserver = observer,
            libraryId = "books",
            sectionType = LibrarySectionType.FINISHED,
        ).first()

        assertEquals(expected, result)
        assertEquals(listOf("books"), observer.finishedLibraryIds)
    }

    @Test
    fun `in progress section observes in-progress items by library id`() = runTest {
        val expected = listOf(item("progress-1"))
        val observer = SectionObserver(inProgress = expected)

        val result = librarySectionItems(
            libraryObserver = observer,
            libraryId = "books",
            sectionType = LibrarySectionType.IN_PROGRESS,
        ).first()

        assertEquals(expected, result)
        assertEquals(listOf("books"), observer.inProgressLibraryIds)
    }

    @Test
    fun `continue series section observes continue-series items by library id`() = runTest {
        val expected = listOf(item("series-1"))
        val observer = SectionObserver(continueSeries = expected)

        val result = librarySectionItems(
            libraryObserver = observer,
            libraryId = "books",
            sectionType = LibrarySectionType.CONTINUE_SERIES,
        ).first()

        assertEquals(expected, result)
        assertEquals(listOf("books"), observer.continueSeriesLibraryIds)
    }

    private fun item(id: String) = LibraryItem(
        id = id,
        libraryId = "books",
        title = id,
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private class SectionObserver(
        private val inProgress: List<LibraryItem> = emptyList(),
        private val finished: List<LibraryItem> = emptyList(),
        private val recentlyAdded: List<LibraryItem> = emptyList(),
        private val continueSeries: List<LibraryItem> = emptyList(),
    ) : LibraryObserver {
        val inProgressLibraryIds = mutableListOf<String>()
        val finishedLibraryIds = mutableListOf<String>()
        val recentlyAddedLibraryIds = mutableListOf<String>()
        val continueSeriesLibraryIds = mutableListOf<String>()

        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> {
            inProgressLibraryIds += libraryId
            return flowOf(inProgress)
        }

        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> {
            finishedLibraryIds += libraryId
            return flowOf(finished)
        }

        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> {
            recentlyAddedLibraryIds += libraryId
            return flowOf(recentlyAdded)
        }

        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> {
            continueSeriesLibraryIds += libraryId
            return flowOf(continueSeries)
        }

        override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = flowOf(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }
}
