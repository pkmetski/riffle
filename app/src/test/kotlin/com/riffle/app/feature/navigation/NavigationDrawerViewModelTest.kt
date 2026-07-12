package com.riffle.app.feature.navigation

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.PendingSource
import java.io.IOException
import com.riffle.core.domain.LastOpenedLibraryStore
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryOrderPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.Series
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
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

    private val serversFlow = MutableStateFlow<List<Source>>(emptyList())
    private val librariesFlow = MutableStateFlow<List<Library>>(emptyList())

    private fun server(
        id: String,
        active: Boolean = false,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ) = Source(
        id = id,
        url = SourceUrl.parse("https://$id.example.com")!!,
        isActive = active,
        insecureConnectionAllowed = false,
        username = "",
        serverType = serverType,
    )

    private fun library(id: String, mediaType: String = "book") =
        Library(id = id, name = id, mediaType = mediaType, isUnsupported = false)

    private var fakeVersions: Map<String, String?> = emptyMap()

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
        override suspend fun getSourceVersion(sourceId: String): String? = fakeVersions[sourceId]
    }

    private fun fakeLibraryRepo(): LibraryObserver = object : LibraryObserver {
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
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private val hiddenFlow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private fun fakeVisibilityStore(): LibraryVisibilityPreferencesStore = object : LibraryVisibilityPreferencesStore {
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
            hiddenFlow.map { it[sourceId].orEmpty() }
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {
            hiddenFlow.update { it + (sourceId to (it[sourceId].orEmpty() + libraryId)) }
        }
        override suspend fun showLibrary(sourceId: String, libraryId: String) {
            hiddenFlow.update { it + (sourceId to (it[sourceId].orEmpty() - libraryId)) }
        }
    }

    private val orderFlow = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private fun fakeOrderStore(): LibraryOrderPreferencesStore = object : LibraryOrderPreferencesStore {
        override fun libraryOrder(sourceId: String): Flow<List<String>> =
            orderFlow.map { it[sourceId].orEmpty() }
        override suspend fun setLibraryOrder(sourceId: String, orderedIds: List<String>) {
            orderFlow.update { it + (sourceId to orderedIds) }
        }
    }

    private val lastOpenedFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun fakeLastOpenedStore(): LastOpenedLibraryStore = object : LastOpenedLibraryStore {
        override fun lastOpenedLibrary(sourceId: String): Flow<String?> =
            lastOpenedFlow.map { it[sourceId] }
        override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
            lastOpenedFlow.update { it + (sourceId to libraryId) }
        }
    }

    private val isOnlineFlow = MutableStateFlow(true)
    private fun fakeConnectivity(): ConnectivityObserver = object : ConnectivityObserver {
        override val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = isOnlineFlow
    }

    private fun makeVm(
        catalogRegistry: com.riffle.core.catalog.CatalogRegistry = fakeCatalogRegistry(),
    ) = NavigationDrawerViewModel(
        sourceRepository = fakeServerRepo(),
        libraryObserver = fakeLibraryRepo(),
        visibilityStore = fakeVisibilityStore(),
        orderStore = fakeOrderStore(),
        lastOpenedLibraryStore = fakeLastOpenedStore(),
        connectivityObserver = fakeConnectivity(),
        catalogRegistry = catalogRegistry,
        nowPlayingNavigator = com.riffle.app.playback.NowPlayingNavigator(),
        nowPlayingStore = com.riffle.app.playback.NowPlayingStore(),
    )

    private fun fakeCatalogRegistry(catalog: com.riffle.core.catalog.Catalog? = null): com.riffle.core.catalog.CatalogRegistry =
        object : com.riffle.core.catalog.CatalogRegistry {
            override suspend fun forActive(): com.riffle.core.catalog.Catalog? = catalog
            override suspend fun forSource(source: com.riffle.core.domain.Source): com.riffle.core.catalog.Catalog? = catalog
            override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog? = catalog
        }

    private object NonDownloadsCatalog : com.riffle.core.catalog.Catalog by CatalogStub

    private object DownloadsCatalog :
        com.riffle.core.catalog.Catalog by CatalogStub,
        com.riffle.core.catalog.DownloadsCapability

    private object CatalogStub : com.riffle.core.catalog.Catalog {
        override val sourceType: com.riffle.core.domain.SourceType = com.riffle.core.domain.SourceType.LOCAL_FILES
        override suspend fun listRoots(): List<com.riffle.core.catalog.CatalogRoot> = emptyList()
        override suspend fun browse(
            rootId: String,
            sort: com.riffle.core.catalog.SortKey,
            page: Int,
            pageSize: Int,
            facet: com.riffle.core.catalog.FacetSelection?,
        ): List<com.riffle.core.catalog.CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<com.riffle.core.catalog.CatalogItem>()
        override suspend fun getItem(itemId: String): com.riffle.core.catalog.CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: com.riffle.core.catalog.BookFormat) = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: com.riffle.core.catalog.BookFormat, handleHint: String?) = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = com.riffle.core.catalog.CatalogHealth(isReachable = true)
    }

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
    fun `setActiveLibrary persists under the repository's active server, not the lagging StateFlow`() = runTest(testDispatcher) {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"))

        val vm = makeVm()
        // Deliberately do NOT collect vm.activeServer, so its eagerly-started StateFlow stays at its
        // initial null — mirroring the window right after a server switch. Persistence must still
        // resolve the active server from the repository (the old activeServer.value path would drop
        // the write or use a stale server here).
        vm.setActiveLibrary("lib-2")
        testScheduler.advanceUntilIdle()

        assertEquals("lib-2", lastOpenedFlow.value["srv-1"])
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
    fun `allServers excludes Storyteller services from the switcher`() = runTest {
        // Storyteller is a Settings-only readaloud backend (ADR 0026) — it must never appear in the
        // drawer's Source Switcher, so it can never become the active browsable server.
        serversFlow.value = listOf(
            server("abs-1", active = true),
            server("st-1", serverType = ServerType.STORYTELLER_SERVICE),
        )

        val vm = makeVm()
        backgroundScope.launch { vm.allServers.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(server("abs-1", active = true)), vm.allServers.value)
    }

    @Test
    fun `visibleLibraries excludes readaloud media-type libraries`() = runTest {
        // The synthetic Readaloud library row exists only as matcher input (ADR 0026); it is never
        // browsable, so it must not show in the drawer even if the active server still owns it.
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("readaloud:srv-1", mediaType = "readaloud"))

        val vm = makeVm()
        backgroundScope.launch { vm.visibleLibraries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(library("lib-1")), vm.visibleLibraries.value)
    }

    @Test
    fun `visibleLibraries applies the saved custom order`() = runTest {
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"), library("lib-3"))
        orderFlow.value = mapOf("srv-1" to listOf("lib-3", "lib-1", "lib-2"))

        val vm = makeVm()
        backgroundScope.launch { vm.visibleLibraries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(library("lib-3"), library("lib-1"), library("lib-2")),
            vm.visibleLibraries.value,
        )
    }

    @Test
    fun `visibleLibraries reorders live when the saved order changes after subscription`() = runTest {
        // Mirrors the real bug report: the drawer is already showing libraries (alphabetical) when
        // the user reorders in Settings — the new order must propagate to the open drawer.
        serversFlow.value = listOf(server("srv-1", active = true))
        librariesFlow.value = listOf(library("lib-1"), library("lib-2"), library("lib-3"))

        val vm = makeVm()
        backgroundScope.launch { vm.visibleLibraries.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf(library("lib-1"), library("lib-2"), library("lib-3")),
            vm.visibleLibraries.value,
        )

        orderFlow.value = mapOf("srv-1" to listOf("lib-2", "lib-3", "lib-1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(library("lib-2"), library("lib-3"), library("lib-1")),
            vm.visibleLibraries.value,
        )
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

    @Test
    fun `serverVersions populated for all servers when online`() = runTest(testDispatcher) {
        fakeVersions = mapOf("srv-1" to "1.2.3", "srv-2" to "2.0.0")
        serversFlow.value = listOf(server("srv-1", active = true), server("srv-2"))
        isOnlineFlow.value = true

        val vm = makeVm()
        backgroundScope.launch { vm.serverVersions.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(mapOf("srv-1" to "1.2.3", "srv-2" to "2.0.0"), vm.serverVersions.value)
    }

    @Test
    fun `serverVersions retries on connectivity change when previous fetch returned null`() = runTest(testDispatcher) {
        // Source unreachable initially — getSourceVersion returns null.
        fakeVersions = mapOf("srv-1" to null)
        serversFlow.value = listOf(server("srv-1", active = true))
        isOnlineFlow.value = false

        val vm = makeVm()
        backgroundScope.launch { vm.serverVersions.collect {} }
        testScheduler.advanceUntilIdle()

        assertTrue(vm.serverVersions.value.isEmpty())

        // Source reachable now; flipping connectivity re-triggers the fetch.
        fakeVersions = mapOf("srv-1" to "1.2.3")
        isOnlineFlow.value = true
        testScheduler.advanceUntilIdle()

        assertEquals(mapOf("srv-1" to "1.2.3"), vm.serverVersions.value)
    }

    // Regression for issue #507: a LocalFiles-only session must not render the drawer's
    // "Downloads" nav link — tapping it would land on an empty destination.
    @Test
    fun `showDownloadsLink is false when no registered Source declares DownloadsCapability`() = runTest(testDispatcher) {
        serversFlow.value = listOf(server("srv-1", active = true))

        val vm = makeVm(catalogRegistry = registryAllReturning(NonDownloadsCatalog))
        backgroundScope.launch { vm.showDownloadsLink.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.showDownloadsLink.value)
    }

    @Test
    fun `showDownloadsLink is true when the active Source declares DownloadsCapability`() = runTest(testDispatcher) {
        serversFlow.value = listOf(server("srv-1", active = true))

        val vm = makeVm(catalogRegistry = registryAllReturning(DownloadsCatalog))
        backgroundScope.launch { vm.showDownloadsLink.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.showDownloadsLink.value)
    }

    // The scope of the link is "any Source", not "active Source" — with a LocalFiles Source
    // active alongside an inactive ABS Source, the user's ABS downloads still need a UI entry
    // point, so the link stays visible.
    @Test
    fun `showDownloadsLink is true when a non-active Source declares DownloadsCapability`() = runTest(testDispatcher) {
        serversFlow.value = listOf(server("srv-local", active = true), server("srv-abs", active = false))

        val vm = makeVm(
            catalogRegistry = object : com.riffle.core.catalog.CatalogRegistry {
                override suspend fun forActive(): com.riffle.core.catalog.Catalog = NonDownloadsCatalog
                override suspend fun forSource(source: com.riffle.core.domain.Source): com.riffle.core.catalog.Catalog =
                    if (source.id == "srv-abs") DownloadsCatalog else NonDownloadsCatalog
                override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog =
                    if (sourceId == "srv-abs") DownloadsCatalog else NonDownloadsCatalog
            }
        )
        backgroundScope.launch { vm.showDownloadsLink.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(true, vm.showDownloadsLink.value)
    }

    private fun registryAllReturning(catalog: com.riffle.core.catalog.Catalog): com.riffle.core.catalog.CatalogRegistry =
        object : com.riffle.core.catalog.CatalogRegistry {
            override suspend fun forActive(): com.riffle.core.catalog.Catalog = catalog
            override suspend fun forSource(source: com.riffle.core.domain.Source): com.riffle.core.catalog.Catalog = catalog
            override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog = catalog
        }
}
