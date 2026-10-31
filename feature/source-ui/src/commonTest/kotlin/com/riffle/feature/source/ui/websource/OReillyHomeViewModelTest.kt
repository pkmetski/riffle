@file:Suppress("ktlint:standard:filename")

package com.riffle.feature.source.ui.websource

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OReillyHomeViewModelCommonTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val libraryId = "oreilly-lib-1"

    private fun libraryItem(id: String, progress: Float) = LibraryItem(
        id = id,
        libraryId = libraryId,
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        readingProgress = progress,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun makeVm(
        inProgress: List<LibraryItem> = emptyList(),
        finished: List<LibraryItem> = emptyList(),
    ): OReillyHomeViewModel {
        val observer = object : StubLibraryObserver() {
            override fun observeInProgressItems(libraryId: String) = flowOf(inProgress)
            override fun observeFinishedItems(libraryId: String) = flowOf(finished)
        }
        return OReillyHomeViewModel(libraryId = libraryId, libraryObserver = observer)
    }

    @Test
    fun inProgressExposesObservedInProgressItems() = runTest {
        val items = listOf(libraryItem("a", 0.45f), libraryItem("b", 0.80f))
        val vm = makeVm(inProgress = items)
        advanceUntilIdle()
        val ip = vm.inProgress.value
        assertEquals(2, ip.size)
        assertTrue(ip.any { it.id == "a" })
        assertTrue(ip.any { it.id == "b" })
    }

    @Test
    fun completedExposesObservedFinishedItems() = runTest {
        val items = listOf(libraryItem("b", 0.90f), libraryItem("c", 1.0f))
        val vm = makeVm(finished = items)
        advanceUntilIdle()
        val c = vm.completed.value
        assertEquals(2, c.size)
        assertTrue(c.any { it.id == "b" })
        assertTrue(c.any { it.id == "c" })
    }

    @Test
    fun isEmptyTrueWhenBothSectionsEmpty() = runTest {
        val vm = makeVm()
        advanceUntilIdle()
        assertTrue(vm.isEmpty.value)
    }

    @Test
    fun isEmptyFalseWhenInProgressHasItems() = runTest {
        val vm = makeVm(inProgress = listOf(libraryItem("a", 0.5f)))
        advanceUntilIdle()
        assertFalse(vm.isEmpty.value)
    }

    @Test
    fun isEmptyFalseWhenCompletedHasItems() = runTest {
        val vm = makeVm(finished = listOf(libraryItem("b", 1.0f)))
        advanceUntilIdle()
        assertFalse(vm.isEmpty.value)
    }
}

private open class StubLibraryObserver : LibraryObserver {
    override fun observeLibraries(): Flow<List<Library>> = flowOf(emptyList())
    override fun observeLibraries(sourceId: String): Flow<List<Library>> = flowOf(emptyList())
    override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
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
