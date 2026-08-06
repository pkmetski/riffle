package com.riffle.app.feature.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

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

    // Pins the fix for the predictive-back burger-menu infinite loop: Compose Navigation 2.8+
    // keeps the previous back-stack entry in composition simultaneously (predictive-back
    // animations), so HOME can be STARTED while library_items is foreground. Without the
    // awaitResumed guard in navigateFromHome, onDestination would fire immediately while HOME is
    // in STARTED state, calling navigateAsRoot(libraryItems) whose popUpTo(HOME) removes the live
    // library_items entry → BackHandler gap → Back falls through → NavHost pops → HOME → loop.
    //
    // Assertion that flips red if awaitResumed() is removed from navigateFromHome: the
    // assertFalse below would fail because onDestination would be called without waiting.
    @Test
    fun `navigateFromHome defers navigation until awaitResumed unblocks`() = runTest {
        serversFlow.value = listOf(server("s", active = true))
        librariesFlow.value = listOf(library("lib-1"))

        val gate = CompletableDeferred<Unit>()
        var navigated = false
        val job = launch {
            navigateFromHome(awaitResumed = { gate.await() }, viewModel = makeVm()) { navigated = true }
        }

        advanceUntilIdle()
        assertFalse("navigateFromHome must not fire before awaitResumed completes", navigated)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("navigateFromHome must fire once awaitResumed completes", navigated)

        job.cancelAndJoin()
    }

    @Test
    fun `navigateFromHome fires immediately when awaitResumed is a no-op`() = runTest {
        serversFlow.value = listOf(server("s", active = true))
        librariesFlow.value = listOf(library("lib-1"))

        var navigated = false
        navigateFromHome(awaitResumed = {}, viewModel = makeVm()) { navigated = true }

        assertTrue("navigateFromHome must fire immediately when awaitResumed does not suspend", navigated)
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

    // Pins the transient-RESUMED guard introduced to fix the burger-menu spinner flash.
    //
    // navigateAsRoot's popUpTo(HOME) step momentarily promotes HOME to RESUMED before
    // navigate(library_route) pushes library_items back on top (demoting HOME to STARTED).
    // Without the yield + lifecycle.currentState re-check, a bare lifecycle.withResumed { }
    // would fire during that transient window and navigateFromHome would navigate a second time,
    // causing the HOME spinner flash.
    //
    // Assertion that flips red if the yield+re-check loop is removed from awaitGenuinelyResumed:
    // `unblocked` would be true after the RESUMED→STARTED pulse instead of remaining false.
    @Test
    fun `awaitGenuinelyResumed does not unblock on a transient RESUMED pulse`() = runTest {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        var unblocked = false

        val job = launch {
            awaitGenuinelyResumed(owner.lifecycle)
            unblocked = true
        }

        // Pulse RESUMED → STARTED: simulates navigateAsRoot's popUpTo(HOME) followed by navigate()
        owner.registry.currentState = Lifecycle.State.RESUMED
        advanceUntilIdle() // lets withResumed fire and the yield() inside awaitGenuinelyResumed run
        owner.registry.currentState = Lifecycle.State.STARTED // navigate(library_route) demotes HOME
        advanceUntilIdle() // loop re-checks lifecycle.currentState; it's STARTED → loop again

        assertFalse("must not unblock on a transient RESUMED pulse", unblocked)
        job.cancelAndJoin()
    }

    @Test
    fun `awaitGenuinelyResumed unblocks when lifecycle is genuinely RESUMED`() = runTest {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        var unblocked = false

        val job = launch {
            awaitGenuinelyResumed(owner.lifecycle)
            unblocked = true
        }

        // Genuine RESUMED: no library is pushed on top — lifecycle stays RESUMED
        owner.registry.currentState = Lifecycle.State.RESUMED
        advanceUntilIdle()

        assertTrue("must unblock when lifecycle is genuinely RESUMED", unblocked)
        job.cancelAndJoin()
    }
}
