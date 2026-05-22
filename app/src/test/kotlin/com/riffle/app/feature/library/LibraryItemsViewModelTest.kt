package com.riffle.app.feature.library

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.domain.Collection
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.Series
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryItemsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private val seriesFlow = MutableStateFlow<List<Series>>(emptyList())
    private val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
    private val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val allItemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())

    private fun fakeRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = MutableStateFlow(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = allItemsFlow
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = itemsFlow
        override fun observeSeries(libraryId: String): Flow<List<Series>> = seriesFlow
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = collectionsFlow
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun refreshLibraries() = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String) = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String) = LibraryRefreshResult.Success
    }

    private fun fakeServerRepo(): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = MutableStateFlow(emptyList())
        override suspend fun getActive(): Server? = null
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
    }

    private fun fakeTokenStorage(): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String): String? = null
        override suspend fun deleteToken(serverId: String) {}
    }

    private fun makeViewModel() = LibraryItemsViewModel(
        SavedStateHandle(mapOf("libraryId" to "lib-1")),
        fakeRepo(),
        fakeServerRepo(),
        fakeTokenStorage(),
    )

    private fun series(name: String) = Series("id-$name", "lib-1", name, null, 1)
    private fun collection(name: String) = Collection("id-$name", "lib-1", name, 1)
    private fun item(title: String, author: String) = LibraryItem(
        "id-$title", "lib-1", title, author, null, 0f, false, false, EbookFormat.Epub,
    )

    // --- empty query passthrough ---

    @Test
    fun `empty query returns all series`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Mistborn"), series("Stormlight")), vm.filteredSeries.value)
    }

    @Test
    fun `empty query returns all collections`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Fantasy"), collection("Sci-Fi")), vm.filteredCollections.value)
    }

    @Test
    fun `empty query returns all ungrouped items`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        itemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov")),
            vm.filteredUngroupedItems.value,
        )
    }

    // --- series filtering ---

    @Test
    fun `query filters series by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("Mistborn"), series("Stormlight Archive"))
        vm.onSearchQueryChange("storm")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("Stormlight Archive")), vm.filteredSeries.value)
    }

    @Test
    fun `series filtering is case-insensitive`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredSeries.collect {} }
        seriesFlow.value = listOf(series("The Wheel of Time"))
        vm.onSearchQueryChange("WHEEL")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(series("The Wheel of Time")), vm.filteredSeries.value)
    }

    // --- collection filtering ---

    @Test
    fun `query filters collections by name`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredCollections.collect {} }
        collectionsFlow.value = listOf(collection("Fantasy"), collection("Sci-Fi"))
        vm.onSearchQueryChange("sci")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(collection("Sci-Fi")), vm.filteredCollections.value)
    }

    // --- item filtering ---

    @Test
    fun `query filters items by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"), item("Foundation", "Isaac Asimov"))
        vm.onSearchQueryChange("dun")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Dune", "Frank Herbert")), vm.filteredUngroupedItems.value)
    }

    @Test
    fun `query filters items by author`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        allItemsFlow.value = listOf(item("Mistborn", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("sand")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("Mistborn", "Brandon Sanderson")), vm.filteredUngroupedItems.value)
    }

    @Test
    fun `query finds books that belong to series by title`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.filteredUngroupedItems.collect {} }
        // allItemsFlow contains items in series; itemsFlow (ungrouped) does not
        allItemsFlow.value = listOf(item("The Final Empire", "Brandon Sanderson"), item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("final empire")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(item("The Final Empire", "Brandon Sanderson")), vm.filteredUngroupedItems.value)
    }

    // --- no match ---

    @Test
    fun `query with no match empties all filtered lists`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch {
            vm.filteredSeries.collect {}
            vm.filteredCollections.collect {}
            vm.filteredUngroupedItems.collect {}
        }
        seriesFlow.value = listOf(series("Mistborn"))
        collectionsFlow.value = listOf(collection("Fantasy"))
        allItemsFlow.value = listOf(item("Dune", "Frank Herbert"))
        vm.onSearchQueryChange("zzznomatch")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Series>(), vm.filteredSeries.value)
        assertEquals(emptyList<Collection>(), vm.filteredCollections.value)
        assertEquals(emptyList<LibraryItem>(), vm.filteredUngroupedItems.value)
    }

    // --- query state ---

    @Test
    fun `onSearchQueryChange updates searchQuery`() = runTest {
        val vm = makeViewModel()
        backgroundScope.launch { vm.searchQuery.collect {} }
        vm.onSearchQueryChange("hello")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello", vm.searchQuery.value)
    }
}
