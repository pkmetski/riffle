package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    ): Pair<GutenbergBrowseViewModel, WebSourceItemGate> {
        val catalog = mockk<Catalog>(relaxed = true).also {
            // Gutendex facets aren't relevant to openDetail; keep the init refresh cheap.
            coEvery { it.listFacets(any()) } returns emptyList()
            coEvery { it.browse(any(), any(), any(), any(), any()) } returns emptyList()
        }
        val registry = mockk<CatalogRegistry>().also { coEvery { it.forSource(any()) } returns catalog }
        val handle = SavedStateHandle(mapOf("libraryId" to GutenbergCatalog.ROOT_BOOKS))
        val vm = GutenbergBrowseViewModel(
            handle,
            sourceRepo,
            registry,
            upserter,
            gate,
            fakeCoverGridDensityStore(),
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

    private fun emptyLibraryObserver(): LibraryObserver = object : LibraryObserver {
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeLibraries() = flowOf(emptyList<com.riffle.core.models.Library>())
        override fun observeLibraries(sourceId: String) = flowOf(emptyList<com.riffle.core.models.Library>())
        override fun observeLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeUngroupedLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeInProgressItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeFinishedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeRecentlyAddedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeSeries(libraryId: String) = flowOf(emptyList<com.riffle.core.models.Series>())
        override fun observeCollections(libraryId: String) = flowOf(emptyList<com.riffle.core.models.Collection>())
        override fun observeSeriesItems(seriesId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeContinueSeriesItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
        override fun observeCollectionItems(collectionId: String) = flowOf(emptyList<LibraryItem>())
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String) = flowOf<LibraryItem?>(null)
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override suspend fun getLibrary(libraryId: String): com.riffle.core.models.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }
}
