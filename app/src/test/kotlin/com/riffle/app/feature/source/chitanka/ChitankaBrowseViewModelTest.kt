package com.riffle.app.feature.source.chitanka

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.app.testing.FakeLibraryObserver
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * Pins openDetail: upserts the tapped [CatalogItem] into `library_items` (so the standard item
 * detail screen can resolve it) and emits an [ChitankaBrowseViewModel.OpenDetailEvent] with the
 * item id.
 *
 * Chitanka's library_items population is on-demand (ADR 0051), so the emission MUST come after the
 * upsert — the screen collects openDetailEvents to navigate. Reversing that order would race
 * `LibraryObserver.getItem` in the detail screen and land it on the "item not found" branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChitankaBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val chitankaSource = Source(
        id = "chit-1",
        url = SourceUrl.parse("https://chitanka.info")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )

    private fun makeVm(
        rootId: String = ChitankaCatalog.ROOT_BOOKS,
        upserter: WebSourceLibraryItemUpserter = mockk(relaxed = true),
        sourceRepo: SourceRepository = fakeSourceRepo(chitankaSource),
        coverGridDensityStore: CoverGridDensityStore = fakeCoverGridDensityStore(),
        libraryFilterPreferencesStore: LibraryFilterPreferencesStore = FakeLibraryFilterPreferencesStore(),
        libraryObserver: LibraryObserver = emptyLibraryObserver(),
        catalog: Catalog = mockk<Catalog>(relaxed = true).also {

            // Relaxed mocks return a stub CatalogItem instead of null for `getItem`, which breaks
            // the openDetail enrichment fallback in tests that don't explicitly stub it. Pin to
            // null so the fallback lands on the listing item as it does at runtime for legacy
            // sources without detail-page metadata.
            coEvery { it.getItem(any()) } returns null
        },
    ): ChitankaBrowseViewModel {
        val registry = mockk<CatalogRegistry>()
        coEvery { registry.forSource(any()) } returns catalog
        val savedStateHandle = SavedStateHandle(mapOf("libraryId" to rootId))
        val gate = mockk<com.riffle.core.data.websource.WebSourceItemGate>(relaxed = true).also {
            // Default: gate reports Fetched — the VM doesn't inspect the outcome, so any concrete
            // outcome value works. Tests that care about specific gate behaviour stub this.
            coEvery { it.openItem(any(), any(), any(), any()) } coAnswers {
                val listing = arg<com.riffle.core.catalog.CatalogItem>(1)
                com.riffle.core.data.websource.WebSourceItemGate.Outcome.Fetched(listing)
            }
        }
        return ChitankaBrowseViewModel(
            savedStateHandle,
            sourceRepo,
            registry,
            upserter,
            gate,
            coverGridDensityStore,
            libraryFilterPreferencesStore,
            libraryObserver,
        )
    }

    private fun item(id: String) = CatalogItem(
        id = id,
        rootId = ChitankaCatalog.ROOT_BOOKS,
        title = id,
        author = "A",
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
    )

    /** Builds a fake Catalog that serves paginated `browse` responses keyed by page index. */
    private fun paginatedCatalog(vararg pages: List<CatalogItem>): Catalog {
        val catalog = mockk<Catalog>(relaxed = true)
        for ((idx, page) in pages.withIndex()) {
            coEvery { catalog.browse(rootId = any(), page = idx, pageSize = any(), facet = any()) } returns page
        }
        // Anything past the enumerated pages is empty — the exhausted-catalogue signal.
        coEvery { catalog.browse(rootId = any(), page = match { it >= pages.size }, pageSize = any(), facet = any()) } returns emptyList()
        return catalog
    }

    private fun catalogEpub() = CatalogItem(
        id = "text/12345-x",
        rootId = ChitankaCatalog.ROOT_BOOKS,
        title = "T",
        author = "A",
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
    )

    private fun catalogAudio() = CatalogItem(
        id = "prikazki/1-slug",
        rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
        title = "T",
        author = "A",
        coverUrl = null,
        ebookFormat = BookFormat.Audiobook,
        hasAudio = true,
    )

    @Test
    fun `openDetail on books root routes through the gate then emits item id`() = runTest(dispatcher) {
        // The VM delegates all persistence to WebSourceItemGate (ADR 0052) — the gate owns
        // caching, refetch, stale-fallback and last-resort listing-item upsert. This test pins
        // the VM contract: pass the listing item to the gate with the active source id, then
        // emit so the screen can navigate.
        val gate = mockk<com.riffle.core.data.websource.WebSourceItemGate>(relaxed = true).also {
            coEvery { it.openItem(any(), any(), any(), any()) } returns
                com.riffle.core.data.websource.WebSourceItemGate.Outcome.Fresh
        }
        val savedStateHandle = SavedStateHandle(mapOf("libraryId" to ChitankaCatalog.ROOT_BOOKS))
        val registry = mockk<CatalogRegistry>().also {
            coEvery { it.forSource(any()) } returns mockk<Catalog>(relaxed = true)
        }
        val vm = ChitankaBrowseViewModel(
            savedStateHandle,
            fakeSourceRepo(chitankaSource),
            registry,
            mockk<WebSourceLibraryItemUpserter>(relaxed = true),
            gate,
            fakeCoverGridDensityStore(),
            FakeLibraryFilterPreferencesStore(),
            emptyLibraryObserver(),
        )
        advanceUntilIdle()

        val item = catalogEpub()
        val emitted = backgroundScope.async(dispatcher) { vm.openDetailEvents.first() }
        vm.openDetail(item)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            gate.openItem(sourceId = "chit-1", listing = item, catalog = any(), forceRefresh = false)
        }
        assertEquals("text/12345-x", emitted.await().itemId)
    }

    @Test
    fun `openDetail on audiobooks root routes through the gate then emits item id`() = runTest(dispatcher) {
        val gate = mockk<com.riffle.core.data.websource.WebSourceItemGate>(relaxed = true).also {
            coEvery { it.openItem(any(), any(), any(), any()) } returns
                com.riffle.core.data.websource.WebSourceItemGate.Outcome.Fresh
        }
        val savedStateHandle = SavedStateHandle(mapOf("libraryId" to ChitankaCatalog.ROOT_AUDIOBOOKS))
        val registry = mockk<CatalogRegistry>().also {
            coEvery { it.forSource(any()) } returns mockk<Catalog>(relaxed = true)
        }
        val vm = ChitankaBrowseViewModel(
            savedStateHandle,
            fakeSourceRepo(chitankaSource),
            registry,
            mockk<WebSourceLibraryItemUpserter>(relaxed = true),
            gate,
            fakeCoverGridDensityStore(),
            FakeLibraryFilterPreferencesStore(),
            emptyLibraryObserver(),
        )
        advanceUntilIdle()

        val item = catalogAudio()
        val emitted = backgroundScope.async(dispatcher) { vm.openDetailEvents.first() }
        vm.openDetail(item)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            gate.openItem(sourceId = "chit-1", listing = item, catalog = any(), forceRefresh = false)
        }
        assertEquals("prikazki/1-slug", emitted.await().itemId)
    }

    @Test
    fun `openDetail with no active Chitanka source no-ops (no gate call, no emit)`() = runTest(dispatcher) {
        val gate = mockk<com.riffle.core.data.websource.WebSourceItemGate>(relaxed = true)
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)
        // Active source is a non-Chitanka type — the ViewModel refuses to touch it.
        val absSource = chitankaSource.copy(id = "abs-1", type = SourceType.ABS)
        val savedStateHandle = SavedStateHandle(mapOf("libraryId" to ChitankaCatalog.ROOT_BOOKS))
        val registry = mockk<CatalogRegistry>().also {
            coEvery { it.forSource(any()) } returns mockk<Catalog>(relaxed = true)
        }
        val vm = ChitankaBrowseViewModel(
            savedStateHandle,
            fakeSourceRepo(absSource),
            registry,
            upserter,
            gate,
            fakeCoverGridDensityStore(),
            FakeLibraryFilterPreferencesStore(),
            emptyLibraryObserver(),
        )
        advanceUntilIdle()

        vm.openDetail(catalogEpub())
        advanceUntilIdle()

        coVerify(exactly = 0) { gate.openItem(any(), any(), any(), any()) }
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test
    fun `UnknownHostException surfaces user-facing offline message, not OkHttp DNS text`() {
        val raw = java.net.UnknownHostException(
            "Unable to resolve host \"chitanka.info\": No address associated with hostname",
        )
        val msg = friendlyErrorMessage(raw)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }

    @Test
    fun `UnknownHostException wrapped in another exception is still recognized`() {
        val wrapped = RuntimeException("boom", java.net.UnknownHostException("chitanka.info"))
        val msg = friendlyErrorMessage(wrapped)
        assertEquals("You appear to be offline. Connect to the internet and try again.", msg)
    }

    @Test
    fun `generic IOException falls back to reachability message`() {
        val msg = friendlyErrorMessage(java.io.IOException("connection reset"))
        assertEquals("Couldn't reach chitanka.info. Check your connection and try again.", msg)
    }

    @Test
    fun `cover scale changes persist through the global density store after debounce`() =
        runTest(dispatcher) {
            val densityStore = RecordingCoverGridDensityStore()
            val vm = makeVm(coverGridDensityStore = densityStore)
            advanceUntilIdle()

            vm.setCoverGridScale(1.4f)
            advanceUntilIdle()

            assertEquals(1.4f, densityStore.persistedScale)
        }

    // ─── Pagination ──────────────────────────────────────────────────────────────────────────────
    //
    // The user reported "not all items in Приказки are shown" — the VM used to only ever request
    // page 0, so anything past position 50 was invisible. Chitanka's browse capability supports a
    // `page` param; these tests pin the incremental append + reset invariants that make lazy
    // scrolling actually paginate.

    private val fullPage = List(50) { item("id-p1-$it") }
    private val fullPage2 = List(50) { item("id-p2-$it") }
    private val shortPage = List(10) { item("id-short-$it") }

    @Test
    fun `loadMore appends next page results`() = runTest(dispatcher) {
        val catalog = paginatedCatalog(fullPage, fullPage2)
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()
        assertEquals(50, vm.items.value.size)
        assertEquals(true, vm.hasMore.value)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(100, vm.items.value.size)
        assertEquals("id-p1-0", vm.items.value.first().id)
        assertEquals("id-p2-49", vm.items.value.last().id)
    }

    @Test
    fun `loadMore stops calling once catalogue returns a short page`() = runTest(dispatcher) {
        val catalog = paginatedCatalog(fullPage, shortPage)
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(60, vm.items.value.size)
        assertEquals(false, vm.hasMore.value)

        // Further scroll-driven calls must be a no-op — no additional catalog.browse invocations.
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 0) {
            catalog.browse(rootId = any(), page = 2, pageSize = any(), facet = any())
        }
    }

    @Test
    fun `loadMore is a no-op when items are empty (nothing to append to)`() = runTest(dispatcher) {
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = any(), pageSize = any(), facet = any()) } returns emptyList()
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()
        assertEquals(0, vm.items.value.size)

        vm.loadMore()
        advanceUntilIdle()

        // Only the init refresh's page-0 call — no follow-up page-1 request.
        coVerify(exactly = 1) { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) }
        coVerify(exactly = 0) { catalog.browse(rootId = any(), page = 1, pageSize = any(), facet = any()) }
    }

    @Test
    fun `selectFacet resets pagination cursor`() = runTest(dispatcher) {
        val catalog = paginatedCatalog(fullPage, fullPage2)
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(100, vm.items.value.size)

        // Selecting a facet re-fetches page 0. The next loadMore must ask for page 1, not page 2.
        vm.selectFacet("some-facet")
        advanceUntilIdle()
        assertEquals(true, vm.hasMore.value)
        vm.loadMore()
        advanceUntilIdle()

        coVerify(atLeast = 1) { catalog.browse(rootId = any(), page = 1, pageSize = any(), facet = any()) }
    }

    @Test
    fun `loadMore dedupes items whose ids overlap with the previous page`() = runTest(dispatcher) {
        // Chitanka's paged views occasionally repeat the tail of the previous page. The VM must
        // append only the new ids so the grid doesn't render duplicates and keys don't collide.
        val page1 = List(50) { item("id-$it") }
        val page2 = List(50) { item("id-${it + 40}") }  // ids 40..89 — overlap ids 40..49
        val catalog = paginatedCatalog(page1, page2)
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        val ids = vm.items.value.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
        assertEquals(90, ids.size)
    }

    @Test
    fun `short first page flips hasMore off immediately (no scroll needed)`() = runTest(dispatcher) {
        val catalog = paginatedCatalog(shortPage)
        val vm = makeVm(catalog = catalog)
        advanceUntilIdle()

        assertEquals(10, vm.items.value.size)
        assertEquals(false, vm.hasMore.value)
    }

    // ---- helpers -----------------------------------------------------------------

    private fun fakeSourceRepo(
        active: Source?,
        allSources: List<Source> = listOfNotNull(active),
    ): SourceRepository = object : SourceRepository {
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(allSources)
        override suspend fun getActive(): Source? = active
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource,
            hiddenLibraryIds: Set<String>,
        ) = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) { }
        override suspend fun remove(sourceId: String) { }
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeCoverGridDensityStore(): CoverGridDensityStore =
        object : CoverGridDensityStore {
            override val scale = flowOf(1f)
            override suspend fun setScale(value: Float) = Unit
            override fun scale(
                sourceId: String, libraryId: String,
                bucket: com.riffle.core.models.ScreenDimensionBucket,
            ) = flowOf(1f)
            override suspend fun setScale(
                sourceId: String, libraryId: String,
                bucket: com.riffle.core.models.ScreenDimensionBucket,
                value: Float,
            ) = Unit
        }

    private class RecordingCoverGridDensityStore : CoverGridDensityStore {
        override val scale = flowOf(1f)
        var persistedScale: Float? = null

        override suspend fun setScale(value: Float) {
            persistedScale = value
        }

        override fun scale(
            sourceId: String, libraryId: String,
            bucket: com.riffle.core.models.ScreenDimensionBucket,
        ) = flowOf(1f)

        override suspend fun setScale(
            sourceId: String, libraryId: String,
            bucket: com.riffle.core.models.ScreenDimensionBucket,
            value: Float,
        ) = Unit
    }

    // ─── Not-Started filter ───────────────────────────────────────────────────────────────────────

    @Test
    fun `toggleNotStartedFilter hides catalog items that have been started in Room`() =
        runTest(dispatcher) {
            val startedId = "text/111"
            val notStartedId = "text/222"
            val neverSeenId = "text/333"

            val startedItem = libraryItem(startedId, progress = 0.5f)
            val notStartedItem = libraryItem(notStartedId, progress = 0f)
            val roomItems = MutableStateFlow(listOf(startedItem, notStartedItem))

            val catalog = mockk<Catalog>(relaxed = true)
            coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
                listOf(item(startedId), item(notStartedId), item(neverSeenId))

            val vm = makeVm(
                catalog = catalog,
                libraryObserver = libraryObserverWithAllBooks(roomItems),
            )
            advanceUntilIdle()

            // Before filter: all three items visible
            assertEquals(3, vm.filteredItems.value.size)
            assertFalse(vm.notStartedFilterActive.value)

            vm.toggleNotStartedFilter()
            advanceUntilIdle()

            // After filter: started item hidden; not-started (progress=0) and never-seen pass through
            assertTrue(vm.notStartedFilterActive.value)
            val ids = vm.filteredItems.value.map { it.id }
            assertEquals(listOf(notStartedId, neverSeenId), ids)
        }

    @Test
    fun `catalog items include local reading progress from Room`() = runTest(dispatcher) {
        val startedId = "text/started"
        val notStartedId = "text/not-started"
        val roomItems = MutableStateFlow(
            listOf(
                libraryItem(startedId, progress = 0.42f),
                libraryItem(notStartedId, progress = 0f),
            ),
        )

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(item(startedId), item(notStartedId), item("text/unseen"))

        val vm = makeVm(
            catalog = catalog,
            libraryObserver = libraryObserverWithAllBooks(roomItems),
        )
        advanceUntilIdle()

        val byId = vm.filteredItems.value.associateBy { it.id }
        assertEquals(0.42f, byId.getValue(startedId).readingProgress ?: -1f)
        assertEquals(0f, byId.getValue(notStartedId).readingProgress ?: -1f)
        assertEquals(null, byId.getValue("text/unseen").readingProgress)
    }

    @Test
    fun `toggleNotStartedFilter off restores all catalog items`() = runTest(dispatcher) {
        val startedId = "text/444"
        val roomItems = MutableStateFlow(listOf(libraryItem(startedId, progress = 0.8f)))

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(item(startedId), item("text/555"))

        val vm = makeVm(catalog = catalog, libraryObserver = libraryObserverWithAllBooks(roomItems))
        advanceUntilIdle()

        vm.toggleNotStartedFilter()
        advanceUntilIdle()
        assertEquals(1, vm.filteredItems.value.size)

        vm.toggleNotStartedFilter()
        advanceUntilIdle()

        assertFalse(vm.notStartedFilterActive.value)
        assertEquals(2, vm.filteredItems.value.size)
    }

    @Test
    fun `remembered filters are scoped separately for Chitanka and Gramofonche roots`() =
        runTest(dispatcher) {
            val store = FakeLibraryFilterPreferencesStore(
                mapOf(
                    ("chit-1" to ChitankaCatalog.ROOT_BOOKS) to LibraryFilterPreferences(
                        selectedFacetKey = "genre:fantasy",
                        notStartedFilterActive = true,
                    ),
                    ("chit-1" to ChitankaCatalog.ROOT_AUDIOBOOKS) to LibraryFilterPreferences(
                        selectedFacetKey = "genre:audio",
                        notStartedFilterActive = false,
                    ),
                ),
            )
            val catalog = mockk<Catalog>(relaxed = true)
            coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns emptyList()

            val vm = makeVm(
                rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
                catalog = catalog,
                libraryFilterPreferencesStore = store,
            )
            advanceUntilIdle()

            assertEquals("genre:audio", vm.selectedFacet.value)
            assertFalse(vm.notStartedFilterActive.value)
            coVerify {
                catalog.browse(
                    rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
                    page = 0,
                    pageSize = any(),
                    facet = FacetSelection("genre:audio"),
                )
            }
        }

    @Test
    fun `selectFacet and not-started changes persist for the active source library`() =
        runTest(dispatcher) {
            val store = FakeLibraryFilterPreferencesStore()
            val vm = makeVm(libraryFilterPreferencesStore = store)
            advanceUntilIdle()

            vm.selectFacet("genre:history")
            vm.toggleNotStartedFilter()
            advanceUntilIdle()

            assertEquals(
                LibraryFilterPreferences(
                    selectedFacetKey = "genre:history",
                    notStartedFilterActive = true,
                ),
                store.state.value["chit-1" to ChitankaCatalog.ROOT_BOOKS],
            )
        }

    // ─── Unowned filter ──────────────────────────────────────────────────────────────────────────

    private val absSource = chitankaSource.copy(id = "abs-1", type = SourceType.ABS)

    @Test
    fun `hasServerSources is false when only web source is configured`() = runTest(dispatcher) {
        val vm = makeVm(sourceRepo = fakeSourceRepo(active = chitankaSource))
        advanceUntilIdle()
        assertFalse(vm.hasServerSources.value)
    }

    @Test
    fun `hasServerSources is true when an ABS source is also configured`() = runTest(dispatcher) {
        val vm = makeVm(
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
        )
        advanceUntilIdle()
        assertTrue(vm.hasServerSources.value)
    }

    @Test
    fun `toggleUnownedFilter hides catalog items that match server library by title+author`() =
        runTest(dispatcher) {
            val serverItem = serverLibraryItem(title = "Dune", author = "Frank Herbert")
            val serverItems = MutableStateFlow(listOf(serverItem))

            val catalog = mockk<Catalog>(relaxed = true)
            coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
                listOf(item("cat-1").copy(title = "Dune", author = "Frank Herbert"), item("cat-2"))

            val vm = makeVm(
                catalog = catalog,
                sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
                libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems),
            )
            advanceUntilIdle()

            assertEquals(2, vm.filteredItems.value.size)
            assertFalse(vm.unownedFilterActive.value)

            vm.toggleUnownedFilter()
            advanceUntilIdle()

            assertTrue(vm.unownedFilterActive.value)
            val ids = vm.filteredItems.value.map { it.id }
            assertEquals(listOf("cat-2"), ids)
        }

    @Test
    fun `toggleUnownedFilter hides catalog items that match server library by isbn`() =
        runTest(dispatcher) {
            val isbn = "9780441013593"
            val serverItem = serverLibraryItem(title = "Dune", author = "Frank Herbert", isbn = isbn)
            val serverItems = MutableStateFlow(listOf(serverItem))

            val catalog = mockk<Catalog>(relaxed = true)
            coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
                listOf(
                    item("cat-1").copy(title = "Dune (Anniversary Edition)", author = "F. Herbert", isbn = isbn),
                    item("cat-2"),
                )

            val vm = makeVm(
                catalog = catalog,
                sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
                libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems),
            )
            advanceUntilIdle()

            vm.toggleUnownedFilter()
            advanceUntilIdle()

            val ids = vm.filteredItems.value.map { it.id }
            assertEquals(listOf("cat-2"), ids)
        }

    @Test
    fun `toggleUnownedFilter off restores all catalog items`() = runTest(dispatcher) {
        val serverItem = serverLibraryItem(title = "Dune", author = "Frank Herbert")
        val serverItems = MutableStateFlow(listOf(serverItem))

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(item("cat-1").copy(title = "Dune", author = "Frank Herbert"), item("cat-2"))

        val vm = makeVm(
            catalog = catalog,
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
            libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems),
        )
        advanceUntilIdle()

        vm.toggleUnownedFilter()
        advanceUntilIdle()
        assertEquals(1, vm.filteredItems.value.size)

        vm.toggleUnownedFilter()
        advanceUntilIdle()

        assertFalse(vm.unownedFilterActive.value)
        assertEquals(2, vm.filteredItems.value.size)
    }

    @Test
    fun `unownedFilter change persists via LibraryFilterPreferencesStore`() = runTest(dispatcher) {
        val store = FakeLibraryFilterPreferencesStore()
        val vm = makeVm(
            libraryFilterPreferencesStore = store,
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
        )
        advanceUntilIdle()

        vm.toggleUnownedFilter()
        advanceUntilIdle()

        assertEquals(
            LibraryFilterPreferences(unownedFilterActive = true),
            store.state.value["chit-1" to ChitankaCatalog.ROOT_BOOKS],
        )
    }

    @Test
    fun `remembered unownedFilterActive is restored from preferences on init`() = runTest(dispatcher) {
        val store = FakeLibraryFilterPreferencesStore(
            mapOf(
                ("chit-1" to ChitankaCatalog.ROOT_BOOKS) to LibraryFilterPreferences(unownedFilterActive = true),
            ),
        )
        val vm = makeVm(libraryFilterPreferencesStore = store)
        advanceUntilIdle()

        assertTrue(vm.unownedFilterActive.value)
    }

    @Test
    fun `server source items are queried on startup to populate progress for browse items`() =
        runTest(dispatcher) {
            // serverSourceProgressByNormKey fires observeAllItemsForSource eagerly so that browse
            // items uploaded to ABS can show their played/in-progress state without requiring the
            // Unowned filter to be active. The buildOwnedItemIndex() path (which caused the original
            // UI-freeze regression) remains gated on _unownedFilterActive — see the test below.
            var queryFired = false
            val spyObserver = object : LibraryObserver by FakeLibraryObserver() {
                override fun observeAllItemsForSource(sourceId: String): Flow<List<LibraryItem>> {
                    queryFired = true
                    return flowOf(emptyList())
                }
            }
            val vm = makeVm(
                sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
                libraryObserver = spyObserver,
            )
            advanceUntilIdle()

            assertTrue("serverSourceProgressByNormKey must query Room on startup for progress", queryFired)
        }

    @Test
    fun `owned item index does not hide items until unowned filter is activated`() = runTest(dispatcher) {
        // buildOwnedItemIndex() is still gated on _unownedFilterActive. Even though server source
        // items are loaded eagerly for progress tracking, the ownership exclusion only takes effect
        // once the user toggles the Unowned filter.
        val serverItem = serverLibraryItem(title = "Dune", author = "Frank Herbert")
        val serverItems = MutableStateFlow(listOf(serverItem))

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(item("cat-1").copy(title = "Dune", author = "Frank Herbert"), item("cat-2"))

        val vm = makeVm(
            catalog = catalog,
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
            libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems),
        )
        advanceUntilIdle()

        // Both items visible before filter is activated — the loaded server items don't trigger hiding
        assertEquals(2, vm.filteredItems.value.size)
        assertFalse(vm.unownedFilterActive.value)

        vm.toggleUnownedFilter()
        advanceUntilIdle()

        // After filter: owned item hidden because buildOwnedItemIndex() ran
        assertTrue(vm.unownedFilterActive.value)
        assertEquals(listOf("cat-2"), vm.filteredItems.value.map { it.id })
    }

    @Test
    fun `catalog items uploaded to ABS show played progress from server source`() = runTest(dispatcher) {
        // Root cause of "Gramofonche uploaded item is not marked as played": localReadingProgressByItemId
        // only queries Chitanka source items (scoped by activeServerId). When a user uploads a
        // Gramofonche item to ABS and listens there, the ABS item's readingProgress is never
        // reflected in the Chitanka browse grid. serverSourceProgressByNormKey fixes this by
        // looking up server-source progress by normalized title+author as a fallback.
        val serverItem = serverLibraryItem(title = "Приказки", author = "Народни приказки")
            .copy(readingProgress = 1.0f)
        val serverItems = MutableStateFlow(listOf(serverItem))

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(
                item("prikazki/1-slug").copy(title = "Приказки", author = "Народни приказки"),
                item("prikazki/2-other"),
            )

        val vm = makeVm(
            rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
            catalog = catalog,
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
            libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems),
        )
        advanceUntilIdle()

        val byId = vm.filteredItems.value.associateBy { it.id }
        assertEquals(1.0f, byId.getValue("prikazki/1-slug").readingProgress)
        assertEquals(null, byId.getValue("prikazki/2-other").readingProgress)
    }

    @Test
    fun `local Chitanka progress takes precedence over server source progress`() = runTest(dispatcher) {
        // If the same item has local progress in the Chitanka source (e.g., user played it in
        // Riffle before uploading) AND server-source progress, the local value wins.
        val itemId = "prikazki/3-test"
        val roomItems = MutableStateFlow(listOf(libraryItem(itemId, progress = 0.7f)
            .copy(libraryId = ChitankaCatalog.ROOT_AUDIOBOOKS)))

        val serverItem = serverLibraryItem(title = itemId, author = "A").copy(readingProgress = 1.0f)
        val serverItems = MutableStateFlow(listOf(serverItem))

        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.browse(rootId = any(), page = 0, pageSize = any(), facet = any()) } returns
            listOf(item(itemId))

        val vm = makeVm(
            rootId = ChitankaCatalog.ROOT_AUDIOBOOKS,
            catalog = catalog,
            sourceRepo = fakeSourceRepo(active = chitankaSource, allSources = listOf(chitankaSource, absSource)),
            libraryObserver = FakeLibraryObserver(allBooksFlow = roomItems, serverSourceItemsFlow = serverItems),
        )
        advanceUntilIdle()

        assertEquals(0.7f, vm.filteredItems.value.first().readingProgress)
    }

    // ---- filter helpers ----------------------------------------------------------

    private fun libraryItem(id: String, progress: Float) = LibraryItem(
        id = id,
        libraryId = ChitankaCatalog.ROOT_BOOKS,
        title = id,
        author = "A",
        coverUrl = null,
        readingProgress = progress,
        isCached = false,
        isDownloaded = false,
        ebookFormat = com.riffle.core.models.EbookFormat.Epub,
    )

    private fun serverLibraryItem(
        title: String,
        author: String,
        isbn: String? = null,
    ) = LibraryItem(
        id = "server-${title.take(4)}",
        libraryId = "abs-lib-1",
        title = title,
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = com.riffle.core.models.EbookFormat.Epub,
        isbn = isbn,
    )

    private fun emptyLibraryObserver(): LibraryObserver = FakeLibraryObserver()

    private fun libraryObserverWithAllBooks(allBooks: Flow<List<LibraryItem>>): LibraryObserver =
        FakeLibraryObserver(allBooksFlow = allBooks)

    private class FakeLibraryFilterPreferencesStore(
        initial: Map<Pair<String, String>, LibraryFilterPreferences> = emptyMap(),
    ) : LibraryFilterPreferencesStore {
        val state = MutableStateFlow(initial)

        override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
            state.map { it[sourceId to libraryId] ?: LibraryFilterPreferences() }

        override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {
            state.update {
                it + ((sourceId to libraryId) to ((it[sourceId to libraryId] ?: LibraryFilterPreferences()).copy(selectedFacetKey = key)))
            }
        }

        override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {
            state.update {
                it + ((sourceId to libraryId) to ((it[sourceId to libraryId] ?: LibraryFilterPreferences()).copy(notStartedFilterActive = active)))
            }
        }

        override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) {
            state.update {
                it + ((sourceId to libraryId) to ((it[sourceId to libraryId] ?: LibraryFilterPreferences()).copy(unownedFilterActive = active)))
            }
        }

        override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {
            state.update {
                it + ((sourceId to libraryId) to ((it[sourceId to libraryId] ?: LibraryFilterPreferences()).copy(sortModeName = name)))
            }
        }
    }
}
