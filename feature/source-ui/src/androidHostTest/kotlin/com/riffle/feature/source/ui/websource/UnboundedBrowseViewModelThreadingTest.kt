package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [UnboundedBrowseViewModel]'s ownership index build and filtered-items combine
 * run on the compute dispatcher rather than the collector thread (Main). Without [flowOn], these
 * computations ran on Main and froze the UI for hundreds of milliseconds on large ABS libraries.
 *
 * Uses [Thread.currentThread] to record which thread runs [buildOwnedItemIndex] — a JVM-only
 * primitive, hence this lives in androidHostTest alongside [LibraryFilterEngineThreadingTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnboundedBrowseViewModelThreadingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chitankaSource = Source(
        id = "chit-1",
        url = SourceUrl.parse("https://chitanka.info")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )
    private val absSource = Source(
        id = "abs-1",
        url = SourceUrl.parse("http://abs.local")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.ABS,
    )

    private fun serverItem(title: String) = LibraryItem(
        id = "srv-$title",
        libraryId = "abs-lib",
        title = title,
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun makeVm(
        serverItems: Flow<List<LibraryItem>>,
        computeDispatcher: DispatcherProvider,
    ): ChitankaBrowseViewModel {
        val sourceRepo = mockk<SourceRepository>().also {
            coEvery { it.getActive() } returns chitankaSource
            coEvery { it.observeAll() } returns MutableStateFlow(listOf(chitankaSource, absSource))
        }
        val catalog = mockk<Catalog>(relaxed = true).also {
            coEvery { it.listFacets(any()) } returns emptyList()
            coEvery { it.browse(any(), any(), any(), any(), any()) } returns listOf(
                CatalogItem(
                    id = "c1",
                    rootId = ChitankaCatalog.ROOT_BOOKS,
                    title = "The Book",
                    author = "A",
                    coverUrl = null,
                    ebookFormat = BookFormat.Epub,
                ),
            )
        }
        val registry = mockk<CatalogRegistry>().also { coEvery { it.forSource(any()) } returns catalog }
        val libraryObserver = FakeLibraryObserver(serverSourceItemsFlow = serverItems)
        val prefs = mockk<LibraryFilterPreferencesStore>().also {
            coEvery { it.preferences(any(), any()) } returns flowOf(
                LibraryFilterPreferences(unownedFilterActive = true),
            )
            coEvery { it.setUnownedFilterActive(any(), any(), any()) } returns Unit
            coEvery { it.setNotStartedFilterActive(any(), any(), any()) } returns Unit
            coEvery { it.setSortModeName(any(), any(), any()) } returns Unit
            coEvery { it.setSelectedFacetKey(any(), any(), any()) } returns Unit
        }
        val coverDensity = mockk<CoverGridDensityStore>(relaxed = true).also {
            coEvery { it.scale } returns MutableStateFlow(1f)
        }
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)
        val gate = mockk<WebSourceItemGate>(relaxed = true)
        return ChitankaBrowseViewModel(
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to ChitankaCatalog.ROOT_BOOKS)),
            sourceRepository = sourceRepo,
            catalogRegistry = registry,
            libraryItemUpserter = upserter,
            webSourceItemGate = gate,
            coverGridDensityStore = coverDensity,
            libraryFilterPreferencesStore = prefs,
            libraryObserver = libraryObserver,
            connectivityObserver = FakeConnectivityObserver(),
            dispatchers = computeDispatcher,
        )
    }

    @Test
    fun `buildOwnedItemIndex runs on compute dispatcher not the collector thread`() = runTest(testDispatcher) {
        val buildThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Wrap the server items flow so we can record which thread processes each emission.
        val rawItems = MutableStateFlow(List(50) { serverItem("Title$it") })
        val instrumentedItems: Flow<List<LibraryItem>> = rawItems.map { items ->
            buildThreads.add(Thread.currentThread().name)
            items
        }

        // Use a dedicated compute dispatcher distinct from testDispatcher (Main) so we can
        // assert the thread name differs.
        val computeDispatcher = object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = testDispatcher
            override val mainImmediate: CoroutineDispatcher get() = testDispatcher
            override val io: CoroutineDispatcher get() = Dispatchers.Default
            override val default: CoroutineDispatcher get() = Dispatchers.Default
        }

        val vm = makeVm(serverItems = instrumentedItems, computeDispatcher = computeDispatcher)
        advanceUntilIdle()

        // Wait until the compute dispatcher has settled: the catalog returns one item that is not
        // owned by any of the 50 server items, so filteredItems emits [catalogItem] once the
        // ownedItemIndex build completes on Dispatchers.Default.  StateFlow.first() returns the
        // current value immediately — use the predicated form so we only proceed after the
        // background pool has actually delivered a result.
        vm.filteredItems.first { it.isNotEmpty() }

        val collectorThread = Thread.currentThread().name
        assertTrue("map inside ownedItemIndex never ran", buildThreads.isNotEmpty())
        assertFalse(
            "ownedItemIndex map ran on collector (Main) thread $collectorThread — got $buildThreads",
            buildThreads.any { it == collectorThread },
        )
    }
}
