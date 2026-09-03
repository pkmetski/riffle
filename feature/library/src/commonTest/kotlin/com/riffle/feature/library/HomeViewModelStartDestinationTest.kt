package com.riffle.feature.library

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.usecase.RefreshLibraries
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelStartDestinationTest {

    private val serversFlow = MutableStateFlow<List<Source>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun source(id: String, active: Boolean = false) = Source(
        id = id,
        url = SourceUrl.parse("https://abs.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "reader",
    )

    private fun library(id: String, name: String = id) = Library(
        id = id,
        name = name,
        mediaType = "book",
        isUnsupported = false,
    )

    private fun makeVm(
        refreshResult: LibraryRefreshResult = LibraryRefreshResult.Success,
    ) = HomeViewModel(
        sourceRepository = object : SourceRepository {
            override fun observeAll(): Flow<List<Source>> = serversFlow
            override suspend fun getActive(): Source? = serversFlow.value.firstOrNull { it.isActive }
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) = throw NotImplementedError()
            override suspend fun setActive(sourceId: String) = throw NotImplementedError()
            override suspend fun remove(sourceId: String) = throw NotImplementedError()
            override suspend fun getSourceVersion(sourceId: String): String? = null
        },
        libraryObserver = object : LibraryObserver {
            override fun observeLibraries(): Flow<List<Library>> = librariesFlow
            override fun observeLibraries(sourceId: String): Flow<List<Library>> = librariesFlow
            override fun observeLibraryItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeUngroupedLibraryItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeInProgressItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeFinishedItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeRecentlyAddedItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeAllBooks(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeSeries(libraryId: String) = flowOf(emptyList<com.riffle.core.models.Series>())
            override fun observeCollections(libraryId: String) = flowOf(emptyList<com.riffle.core.models.Collection>())
            override fun observeSeriesItems(seriesId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeContinueSeriesItems(libraryId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override fun observeCollectionItems(collectionId: String) = flowOf(emptyList<com.riffle.core.models.LibraryItem>())
            override suspend fun getItem(itemId: String) = null
            override fun observeItem(itemId: String) = flowOf(null)
            override suspend fun getItem(sourceId: String, itemId: String) = null
            override suspend fun getLibrary(libraryId: String) = null
            override suspend fun getSeriesIdForItem(sourceId: String, itemId: String) = null
        },
        refreshLibraries = object : RefreshLibraries(object : com.riffle.core.domain.LibraryRefresher {
            override suspend fun refreshLibraries() = refreshResult
            override suspend fun refreshLibraryItems(libraryId: String) = refreshResult
            override suspend fun refreshSeries(libraryId: String) = refreshResult
            override suspend fun refreshCollections(libraryId: String) = refreshResult
            override suspend fun refreshItemProgress(sourceId: String, itemId: String) = refreshResult
        }) {},
        visibilityStore = object : LibraryVisibilityPreferencesStore {
            override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> = flowOf(emptySet())
            override suspend fun hideLibrary(sourceId: String, libraryId: String) {}
            override suspend fun showLibrary(sourceId: String, libraryId: String) {}
        },
        lastOpenedLibraryStore = object : LastOpenedLibraryStore {
            override fun lastOpenedLibrary(sourceId: String): Flow<String?> = flowOf(null)
            override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {}
        },
        dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Main
            override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main
            override val io: CoroutineDispatcher get() = Dispatchers.Default
            override val default: CoroutineDispatcher get() = Dispatchers.Default
        },
    )

    @Test
    fun `getStartDestination returns AddSource when no sources`() = runTest {
        val result = makeVm().getStartDestination()
        assertEquals(HomeViewModel.StartDestination.AddSource, result)
    }

    @Test
    fun `getStartDestination returns Library when sources and libraries exist`() = runTest {
        serversFlow.value = listOf(source("s1", active = true))
        librariesFlow.value = listOf(library("lib-1", name = "Fantasy"))

        val result = makeVm().getStartDestination()

        assertEquals(
            HomeViewModel.StartDestination.Library(SourceType.ABS, "lib-1", "Fantasy"),
            result,
        )
    }

    @Test
    fun `getStartDestination triggers refresh when library cache is empty`() = runTest {
        serversFlow.value = listOf(source("s1", active = true))
        var refreshCalled = false
        val vm = makeVm(refreshResult = LibraryRefreshResult.Success.also {
            // After refresh, populate libraries so the VM picks one
        })
        // Libraries remain empty → refresh fires → still empty → AddSource (no libraries state)
        val result = makeVm(refreshResult = LibraryRefreshResult.NetworkError(Exception("offline"))).getStartDestination()
        assertEquals(HomeViewModel.StartDestination.NoLibraries, result)
    }
}
