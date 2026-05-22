package com.riffle.app.feature.navigation

import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.Series
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationDrawerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val serversFlow = MutableStateFlow<List<Server>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())

    private fun server(id: String, active: Boolean = false) = Server(
        id = id,
        url = ServerUrl.parse("https://$id.example.com")!!,
        displayName = id,
        isActive = active,
        insecureConnectionAllowed = false,
    )

    private fun library(id: String) = Library(id = id, name = id, mediaType = "book", isUnsupported = false)

    private fun fakeServerRepo(): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = serversFlow
        override suspend fun getActive(): Server? = serversFlow.value.firstOrNull { it.isActive }
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
            AddServerResult.WrongCredentials()
        override suspend fun setActive(serverId: String) {
            serversFlow.update { list -> list.map { it.copy(isActive = it.id == serverId) } }
        }
        override suspend fun remove(serverId: String) {
            serversFlow.update { list -> list.filter { it.id != serverId } }
        }
    }

    private fun fakeLibraryRepo(): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> =
            hiddenFlow.map { it[serverId].orEmpty() }
        override suspend fun hideLibrary(serverId: String, libraryId: String) {
            hiddenFlow.update { it + (serverId to (it[serverId].orEmpty() + libraryId)) }
        }
        override suspend fun showLibrary(serverId: String, libraryId: String) {
            hiddenFlow.update { it + (serverId to (it[serverId].orEmpty() - libraryId)) }
        }
    }

    private fun makeVm() = NavigationDrawerViewModel(
        serverRepository = fakeServerRepo(),
        libraryRepository = fakeLibraryRepo(),
        visibilityStore = fakeVisibilityStore(),
    )

    @Test
    fun `redirectToLibrary emits next visible library when active library is hidden`() = runTest(testDispatcher) {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))

        val vm = makeVm()
        vm.setActiveLibrary("lib-1")

        // Collect the first redirect in the test scope (not backgroundScope) so
        // advanceUntilIdle drives it — backgroundScope coroutines are not advanced by it.
        val redirect = async { vm.redirectToLibrary.first() }
        testScheduler.advanceUntilIdle()

        assertTrue("no redirect while lib-1 is still visible", !redirect.isCompleted)

        hiddenFlow.value = mapOf("srv-1" to setOf("lib-1"))
        testScheduler.advanceUntilIdle()

        assertEquals(library("lib-2"), redirect.await())
    }

    @Test
    fun `redirectToLibrary does not emit when active library is still visible`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))

        val vm = makeVm()
        vm.setActiveLibrary("lib-1")

        val redirects = mutableListOf<Library>()
        backgroundScope.launch { vm.redirectToLibrary.collect { redirects.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        // Only lib-2 is hidden — lib-1 is still visible
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-2"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(redirects.isEmpty())
    }

    @Test
    fun `redirectToLibrary does not emit when no visible libraries remain`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"))

        val vm = makeVm()
        vm.setActiveLibrary("lib-1")

        val redirects = mutableListOf<Library>()
        backgroundScope.launch { vm.redirectToLibrary.collect { redirects.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        hiddenFlow.value = mapOf("srv-1" to setOf("lib-1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(redirects.isEmpty())
    }

    @Test
    fun `diagnostic - visibleLibraries updates after hiding`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))

        val vm = makeVm()
        vm.setActiveLibrary("lib-1")

        backgroundScope.launch { vm.visibleLibraries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("visibleLibraries should have both initially",
            listOf(library("lib-1"), library("lib-2")), vm.visibleLibraries.value)

        hiddenFlow.value = mapOf("srv-1" to setOf("lib-1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("visibleLibraries should only have lib-2 after hiding lib-1",
            listOf(library("lib-2")), vm.visibleLibraries.value)
    }

    @Test
    fun `visibleLibraries excludes hidden library IDs`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"), library("lib-3"))
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-2"))

        val vm = makeVm()
        backgroundScope.launch { vm.visibleLibraries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(library("lib-1"), library("lib-3")), vm.visibleLibraries.value)
    }

    @Test
    fun `switching servers updates visibleLibraries to the new server libraries`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true), server("srv-2"))
        librariesFlow.value = listOf(library("lib-A"))

        val vm = makeVm()
        backgroundScope.launch { vm.visibleLibraries.collect {} }
        backgroundScope.launch { vm.activeServer.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(library("lib-A")), vm.visibleLibraries.value)

        // Switch server — the library repo emits new libs for the new active server
        serversFlow.update { list -> list.map { it.copy(isActive = it.id == "srv-2") } }
        librariesFlow.value = listOf(library("lib-B"), library("lib-C"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(library("lib-B"), library("lib-C")), vm.visibleLibraries.value)
    }
}
