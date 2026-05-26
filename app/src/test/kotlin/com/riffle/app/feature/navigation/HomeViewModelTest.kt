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
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val serversFlow = MutableStateFlow<List<Server>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())
    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private fun server(id: String, active: Boolean = false) = Server(
        id = id,
        url = ServerUrl.parse("https://$id.example.com")!!,
        displayName = id,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
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
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeLibraryRepo(
        onRefresh: () -> Unit = {},
        refreshResult: LibraryRefreshResult = LibraryRefreshResult.Success,
    ): LibraryRepository = object : LibraryRepository {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override suspend fun refreshLibraries(): LibraryRefreshResult { onRefresh(); return refreshResult }
        override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
        override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    }

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> = hiddenFlow.map { it[serverId].orEmpty() }
        override suspend fun hideLibrary(serverId: String, libraryId: String) {}
        override suspend fun showLibrary(serverId: String, libraryId: String) {}
    }

    private fun makeVm(libraryRepo: LibraryRepository = fakeLibraryRepo()) = HomeViewModel(
        serverRepository = fakeServerRepo(),
        libraryRepository = libraryRepo,
        visibilityStore = fakeVisibilityStore(),
    )

    @Test
    fun `getStartDestination returns AddServer when no servers`() = runTest {
        val result = makeVm().getStartDestination()
        assertEquals(HomeViewModel.StartDestination.AddServer, result)
    }

    @Test
    fun `getStartDestination returns Library when libraries are already cached`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
    }

    @Test
    fun `getStartDestination fetches libraries on fresh login when DB is empty`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        // librariesFlow starts empty — simulates first login before any cache

        val repo = fakeLibraryRepo(onRefresh = { librariesFlow.value = listOf(library("lib-1")) })
        val result = makeVm(repo).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
    }

    @Test
    fun `getStartDestination returns AddServer when server exists but refresh yields no libraries`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        // libraries remain empty even after a successful refresh — server has no book libraries

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.AddServer, result)
    }

    @Test
    fun `getStartDestination returns NoLibraries when refresh fails with network error`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val repo = fakeLibraryRepo(refreshResult = LibraryRefreshResult.NetworkError(IOException("Connection refused")))

        val result = makeVm(repo).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.NoLibraries, result)
    }

    @Test
    fun `getStartDestination returns NoLibraries when refresh fails with no active server`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val repo = fakeLibraryRepo(refreshResult = LibraryRefreshResult.NoActiveServer)

        val result = makeVm(repo).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.NoLibraries, result)
    }

    @Test
    fun `getStartDestination skips hidden libraries and navigates to first visible`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-1"))

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-2", "lib-2"), result)
    }
}
