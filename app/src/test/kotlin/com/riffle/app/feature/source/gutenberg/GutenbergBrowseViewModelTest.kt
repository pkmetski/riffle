package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.testing.FakeLibraryObserver
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetedSearchCapability
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
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
import org.junit.Before
import org.junit.Test

/**
 * Pins that Gutenberg's browse VM routes item taps through [WebSourceItemGate] — the same
 * ADR-0043 caching path used by every other web source. If this test flips red, Gutenberg
 * silently regressed to hitting Gutendex on every tap (or worse, lost its stale-fallback
 * behaviour and started dead-ending taps whenever the detail fetch fails).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GutenbergBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val gutenbergSource = Source(
        id = "gut-1",
        url = SourceUrl.parse("https://gutendex.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.GUTENBERG,
    )

    private fun sampleItem() = CatalogItem(
        id = "84",
        rootId = GutenbergCatalog.ROOT_BOOKS,
        title = "Frankenstein",
        author = "Mary Shelley",
        coverUrl = "https://gutenberg.org/cache/epub/84/pg84.cover.medium.jpg",
        ebookFormat = BookFormat.Epub,
    )

    private fun makeVm(
        sourceRepo: SourceRepository = fakeSourceRepo(gutenbergSource),
        gate: WebSourceItemGate = mockk(relaxed = true) {
            coEvery { openItem(any(), any(), any(), any()) } returns WebSourceItemGate.Outcome.Fresh
        },
        upserter: WebSourceLibraryItemUpserter = mockk(relaxed = true),
        libraryFilterPreferencesStore: LibraryFilterPreferencesStore = FakeLibraryFilterPreferencesStore(),
        catalog: Catalog = mockk<Catalog>(relaxed = true).also {
            // Gutendex facets aren't relevant to openDetail; keep the init refresh cheap.
            coEvery { it.listFacets(any()) } returns emptyList()
            coEvery { it.browse(any(), any(), any(), any(), any()) } returns emptyList()
        },
    ): Pair<GutenbergBrowseViewModel, WebSourceItemGate> {
        val registry = mockk<CatalogRegistry>().also { coEvery { it.forSource(any()) } returns catalog }
        val handle = SavedStateHandle(mapOf("libraryId" to GutenbergCatalog.ROOT_BOOKS))
        val vm = GutenbergBrowseViewModel(
            handle,
            sourceRepo,
            registry,
            upserter,
            gate,
            fakeCoverGridDensityStore(),
            libraryFilterPreferencesStore,
            emptyLibraryObserver(),
        )
        return vm to gate
    }

    @Test
    fun `openDetail routes through the gate then emits item id`() = runTest(dispatcher) {
        val (vm, gate) = makeVm()
        advanceUntilIdle()

        val item = sampleItem()
        val emitted = backgroundScope.async(dispatcher) { vm.openDetailEvents.first() }
        vm.openDetail(item)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            gate.openItem(sourceId = "gut-1", listing = item, catalog = any(), forceRefresh = false)
        }
        assertEquals("84", emitted.await().itemId)
    }

    @Test
    fun `openDetail with no active Gutenberg source no-ops (no gate call, no emit)`() = runTest(dispatcher) {
        val absSource = gutenbergSource.copy(id = "abs-1", type = SourceType.ABS)
        val (vm, gate) = makeVm(sourceRepo = fakeSourceRepo(absSource))
        advanceUntilIdle()

        vm.openDetail(sampleItem())
        advanceUntilIdle()

        coVerify(exactly = 0) { gate.openItem(any(), any(), any(), any()) }
    }

    @Test
    fun `search keeps the selected language facet when the catalog supports faceted search`() = runTest(dispatcher) {
        val catalog = RecordingFacetedSearchCatalog()
        val (vm, _) = makeVm(catalog = catalog)
        advanceUntilIdle()

        vm.selectFacet("language:fr")
        advanceUntilIdle()
        vm.onQueryChange("dumas")
        advanceUntilIdle()

        assertEquals(FacetSelection("language:fr"), catalog.lastSearchFacet)
        assertEquals("dumas", catalog.lastSearchQuery)
    }

    @Test
    fun `remembered language facet is used for initial Project Gutenberg browse`() = runTest(dispatcher) {
        val store = FakeLibraryFilterPreferencesStore(
            mapOf(
                ("gut-1" to GutenbergCatalog.ROOT_BOOKS) to LibraryFilterPreferences(
                    selectedFacetKey = "language:fr",
                    notStartedFilterActive = true,
                ),
            ),
        )
        val catalog = RecordingFacetedSearchCatalog()
        val (vm, _) = makeVm(catalog = catalog, libraryFilterPreferencesStore = store)
        advanceUntilIdle()

        assertEquals("language:fr", vm.selectedFacet.value)
        assertEquals(true, vm.notStartedFilterActive.value)
        assertEquals(FacetSelection("language:fr"), catalog.lastBrowseFacet)
    }

    @Test
    fun `facet and not-started changes are persisted for Project Gutenberg`() = runTest(dispatcher) {
        val store = FakeLibraryFilterPreferencesStore()
        val (vm, _) = makeVm(libraryFilterPreferencesStore = store)
        advanceUntilIdle()

        vm.selectFacet("topic:history")
        vm.toggleNotStartedFilter()
        advanceUntilIdle()

        assertEquals(
            LibraryFilterPreferences(
                selectedFacetKey = "topic:history",
                notStartedFilterActive = true,
            ),
            store.state.value["gut-1" to GutenbergCatalog.ROOT_BOOKS],
        )
    }

    private fun fakeSourceRepo(active: Source?): SourceRepository = object : SourceRepository {
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(listOfNotNull(active))
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
        }

    private fun emptyLibraryObserver() = FakeLibraryObserver()

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

        override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {
            state.update {
                it + ((sourceId to libraryId) to ((it[sourceId to libraryId] ?: LibraryFilterPreferences()).copy(sortModeName = name)))
            }
        }
    }

    private inner class RecordingFacetedSearchCatalog : Catalog, FacetedSearchCapability {
        var lastSearchFacet: FacetSelection? = null
        var lastSearchQuery: String? = null
        var lastBrowseFacet: FacetSelection? = null

        override val sourceType: SourceType = SourceType.GUTENBERG

        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(
            rootId: String,
            sort: SortKey,
            page: Int,
            pageSize: Int,
            facet: FacetSelection?,
        ): List<CatalogItem> {
            lastBrowseFacet = facet
            return emptyList()
        }

        override suspend fun search(
            rootId: String,
            query: String,
            page: Int,
            pageSize: Int,
        ): List<CatalogItem> = emptyList()

        override suspend fun search(
            rootId: String,
            query: String,
            page: Int,
            pageSize: Int,
            facet: FacetSelection?,
        ): List<CatalogItem> {
            lastSearchFacet = facet
            lastSearchQuery = query
            return emptyList()
        }

        override suspend fun getItem(itemId: String): CatalogItem? = null

        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle {
            throw UnsupportedOperationException()
        }

        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T {
            throw UnsupportedOperationException()
        }

        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }
}
