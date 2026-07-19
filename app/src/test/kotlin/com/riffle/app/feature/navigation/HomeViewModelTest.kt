package com.riffle.app.feature.navigation

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.models.Collection
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.PendingSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.models.Series
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceUrl
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

    private val serversFlow = MutableStateFlow<List<Source>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())
    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val lastOpenedFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun server(id: String, active: Boolean = false) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun library(id: String) = Library(id = id, name = id, mediaType = "book", isUnsupported = false)

    private fun fakeServerRepo(): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = serversFlow
        override suspend fun getActive(): Source? = serversFlow.value.firstOrNull { it.isActive }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(IOException())
        override suspend fun setActive(sourceId: String) {
            serversFlow.update { list -> list.map { it.copy(isActive = it.id == sourceId) } }
        }
        override suspend fun remove(sourceId: String) {
            serversFlow.update { list -> list.filter { it.id != sourceId } }
        }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeRefreshLibraries(
        val onRefresh: () -> Unit = {},
        val refreshResult: LibraryRefreshResult = LibraryRefreshResult.Success,
    ) : com.riffle.core.domain.usecase.RefreshLibraries(com.riffle.app.testing.NoopLibraryRefresher) {
        override suspend fun invoke(): LibraryRefreshResult { onRefresh(); return refreshResult }
    }

    private fun fakeLibraryRepo(
        onRefresh: () -> Unit = {},
        refreshResult: LibraryRefreshResult = LibraryRefreshResult.Success,
    ): LibraryObserver = object : LibraryObserver {
        override fun observeLibraries(): Flow<List<Library>> = librariesFlow
        override fun observeLibraries(sourceId: String): Flow<List<Library>> = observeLibraries()
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<Series>> = MutableStateFlow(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<Collection>> = MutableStateFlow(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = MutableStateFlow(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = getItem(itemId)
        override suspend fun getLibrary(libraryId: String): com.riffle.core.models.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> = hiddenFlow.map { it[sourceId].orEmpty() }
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {}
        override suspend fun showLibrary(sourceId: String, libraryId: String) {}
    }

    private fun fakeLastOpenedStore(): LastOpenedLibraryStore = object : LastOpenedLibraryStore {
        override fun lastOpenedLibrary(sourceId: String): Flow<String?> =
            lastOpenedFlow.map { it[sourceId] }
        override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
            lastOpenedFlow.update { it + (sourceId to libraryId) }
        }
    }

    private val unconfinedDispatchers = object : DispatcherProvider {
        private val d: CoroutineDispatcher = Dispatchers.Unconfined
        override val main = d
        override val mainImmediate = d
        override val io = d
        override val default = d
    }

    private fun makeVm(
        libraryRepo: LibraryObserver = fakeLibraryRepo(),
        refreshLibraries: com.riffle.core.domain.usecase.RefreshLibraries = FakeRefreshLibraries(),
    ) = HomeViewModel(
        sourceRepository = fakeServerRepo(),
        libraryObserver = libraryRepo,
        refreshLibraries = refreshLibraries,
        visibilityStore = fakeVisibilityStore(),
        lastOpenedLibraryStore = fakeLastOpenedStore(),
        dispatchers = unconfinedDispatchers,
    )

    @Test
    fun `getStartDestination returns AddSource when no servers`() = runTest {
        val result = makeVm().getStartDestination()
        assertEquals(HomeViewModel.StartDestination.AddSource, result)
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

        val refresh = FakeRefreshLibraries(onRefresh = { librariesFlow.value = listOf(library("lib-1")) })
        val result = makeVm(refreshLibraries = refresh).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
    }

    @Test
    fun `getStartDestination returns AddSource when server exists but refresh yields no libraries`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        // libraries remain empty even after a successful refresh — server has no book libraries

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.AddSource, result)
    }

    @Test
    fun `getStartDestination returns NoLibraries when refresh fails with network error`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val refresh = FakeRefreshLibraries(refreshResult = LibraryRefreshResult.NetworkError(IOException("Connection refused")))

        val result = makeVm(refreshLibraries = refresh).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.NoLibraries, result)
    }

    @Test
    fun `getStartDestination returns NoLibraries when refresh fails with no active server`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        val refresh = FakeRefreshLibraries(refreshResult = LibraryRefreshResult.NoActiveServer)

        val result = makeVm(refreshLibraries = refresh).getStartDestination()

        assertEquals(HomeViewModel.StartDestination.NoLibraries, result)
    }

    @Test
    fun `getStartDestination reopens the last opened library when still visible`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"), library("lib-3"))
        lastOpenedFlow.value = mapOf("srv-1" to "lib-2")

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-2", "lib-2"), result)
    }

    @Test
    fun `getStartDestination falls back to first visible when last opened library is now hidden`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        lastOpenedFlow.value = mapOf("srv-1" to "lib-2")
        hiddenFlow.value = mapOf("srv-1" to setOf("lib-2"))

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
    }

    @Test
    fun `getStartDestination falls back to first visible when last opened library no longer exists`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        lastOpenedFlow.value = mapOf("srv-1" to "lib-gone")

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
    }

    @Test
    fun `getStartDestination ignores a last opened library remembered for a different server`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))
        // Remembered for srv-2, not the active srv-1.
        lastOpenedFlow.value = mapOf("srv-2" to "lib-2")

        val result = makeVm().getStartDestination()

        assertEquals(HomeViewModel.StartDestination.Library("lib-1", "lib-1"), result)
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
