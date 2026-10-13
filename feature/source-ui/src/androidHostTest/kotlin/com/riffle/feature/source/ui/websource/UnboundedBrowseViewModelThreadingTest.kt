package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
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

    // Dedicated single thread used as the compute dispatcher in the threading test. Using
    // Dispatchers.Default caused flakiness in the full :feature:source-ui:testAndroidHostTest
    // run because the shared Default pool could be saturated by other tests, causing the
    // 60 s runTest timeout to fire before first{} returned. A private thread is always
    // available and still has a distinct thread ID from testDispatcher (Main).
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private val computeThread = newSingleThreadContext("test-compute")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        computeThread.close()
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
        coverGridDensityStore: CoverGridDensityStore = mockk<CoverGridDensityStore>(relaxed = true).also {
            every { it.scale } returns MutableStateFlow(1f)
            every { it.homeScale } returns MutableStateFlow(1f)
        },
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
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)
        val gate = mockk<WebSourceItemGate>(relaxed = true)
        return ChitankaBrowseViewModel(
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to ChitankaCatalog.ROOT_BOOKS)),
            sourceRepository = sourceRepo,
            catalogRegistry = registry,
            libraryItemUpserter = upserter,
            webSourceItemGate = gate,
            coverGridDensityStore = coverGridDensityStore,
            libraryFilterPreferencesStore = prefs,
            libraryObserver = libraryObserver,
            connectivityObserver = FakeConnectivityObserver(),
            dispatchers = computeDispatcher,
        )
    }

    @Test
    fun `buildOwnedItemIndex runs on compute dispatcher not the collector thread`() = runTest(testDispatcher) {
        // Use thread IDs (unique per JVM instance) rather than names; on CI the Gradle executor
        // thread is sometimes named "DefaultDispatcher-worker-1", which would collide with a real
        // background worker and cause a spurious assertion failure on the name comparison.
        val buildThreadIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        // Wrap the server items flow so we can record which thread processes each emission.
        val rawItems = MutableStateFlow(List(50) { serverItem("Title$it") })
        val instrumentedItems: Flow<List<LibraryItem>> = rawItems.map { items ->
            buildThreadIds.add(Thread.currentThread().threadId())
            items
        }

        // Use the dedicated single-thread compute dispatcher (not Dispatchers.Default) so the
        // thread is always available regardless of what other tests in the suite are doing.
        val computeDispatcher = object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = testDispatcher
            override val mainImmediate: CoroutineDispatcher get() = testDispatcher
            override val io: CoroutineDispatcher get() = computeThread
            override val default: CoroutineDispatcher get() = computeThread
        }

        val vm = makeVm(serverItems = instrumentedItems, computeDispatcher = computeDispatcher)
        advanceUntilIdle()

        // Capture the collector thread ID BEFORE first{} to avoid a race where the test thread
        // is parked between first{} returning and the capture, and its ID momentarily matches a
        // reused worker slot (highly unlikely but a defensive belt-and-suspenders measure).
        val collectorThreadId = Thread.currentThread().threadId()

        // Wait until the compute dispatcher has settled: the catalog returns one item that is not
        // owned by any of the 50 server items, so filteredItems emits [catalogItem] once the
        // ownedItemIndex build completes on Dispatchers.Default.  StateFlow.first() returns the
        // current value immediately — require buildThreadIds.isNotEmpty() in the predicate so we
        // only proceed once the background pool has *actually* run the map. Without this, the
        // initial non-empty server-item list can satisfy it.isNotEmpty() before the index builds.
        vm.filteredItems.first { it.isNotEmpty() && buildThreadIds.isNotEmpty() }

        assertTrue("map inside ownedItemIndex never ran", buildThreadIds.isNotEmpty())
        assertFalse(
            "ownedItemIndex map ran on collector (Main) thread id=$collectorThreadId — got ids $buildThreadIds",
            buildThreadIds.any { it == collectorThreadId },
        )
        // Cancel viewModelScope and join to prevent UncompletedCoroutinesError: the ViewModel's
        // SharingStarted.Eagerly stateIn flows keep coroutines active in viewModelScope
        // indefinitely, which runTest detects as uncompleted after the test body finishes.
        // join() waits for the computeThread coroutines to reach a terminal cancelled state.
        vm.viewModelScope.cancel()
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
    }

    @Test
    fun homeCoverGridScaleReadFromHomeScaleNotGlobalScale() = runTest(testDispatcher) {
        val store = mockk<CoverGridDensityStore>(relaxed = true).also {
            every { it.scale } returns MutableStateFlow(1f)
            every { it.homeScale } returns MutableStateFlow(1.8f)
        }
        val vm = makeVm(
            serverItems = flowOf(emptyList()),
            computeDispatcher = object : DispatcherProvider {
                override val main: CoroutineDispatcher get() = testDispatcher
                override val mainImmediate: CoroutineDispatcher get() = testDispatcher
                override val io: CoroutineDispatcher get() = testDispatcher
                override val default: CoroutineDispatcher get() = testDispatcher
            },
            coverGridDensityStore = store,
        )
        backgroundScope.launch { vm.homeCoverGridScale.collect {} }
        advanceUntilIdle()

        assertEquals(1.8f, vm.homeCoverGridScale.value)
    }

    @Test
    fun setHomeCoverGridScaleCallsSetHomeScaleNotSetScale() = runTest(testDispatcher) {
        val store = mockk<CoverGridDensityStore>(relaxed = true).also {
            every { it.scale } returns MutableStateFlow(1f)
            every { it.homeScale } returns MutableStateFlow(1f)
        }
        val vm = makeVm(
            serverItems = flowOf(emptyList()),
            computeDispatcher = object : DispatcherProvider {
                override val main: CoroutineDispatcher get() = testDispatcher
                override val mainImmediate: CoroutineDispatcher get() = testDispatcher
                override val io: CoroutineDispatcher get() = testDispatcher
                override val default: CoroutineDispatcher get() = testDispatcher
            },
            coverGridDensityStore = store,
        )

        vm.setHomeCoverGridScale(1.4f)
        testDispatcher.scheduler.advanceTimeBy(201)
        advanceUntilIdle()

        coVerify(exactly = 1) { store.setHomeScale(1.4f) }
        coVerify(exactly = 0) { store.setScale(any<Float>()) }
    }
}
