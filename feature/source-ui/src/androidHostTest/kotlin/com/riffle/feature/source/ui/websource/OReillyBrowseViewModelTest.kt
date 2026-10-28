package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.oreilly.OReillyCatalog
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
 * Pins that the O'Reilly browse VM routes item taps through [WebSourceItemGate] and emits
 * [UnboundedBrowseViewModel.openDetailEvents]. This is the ViewModel half of the fix for the
 * stale-closure navigation bug: the screen's LaunchedEffect(viewModel) collects openDetailEvents
 * and routes to the detail screen; if the emit never fires, navigation silently fails regardless
 * of the Compose layer fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OReillyBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val oreillySource = Source(
        id = "oreilly-1",
        url = SourceUrl.parse("https://learning.oreilly.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.OREILLY,
    )

    private fun sampleItem() = CatalogItem(
        id = "9781492032649",
        rootId = OReillyCatalog.ROOT_BOOKS,
        title = "Designing Data-Intensive Applications",
        author = "Martin Kleppmann",
        coverUrl = "https://learning.oreilly.com/library/cover/9781492032649/600/",
        ebookFormat = BookFormat.Epub,
    )

    private fun makeVm(
        sourceRepo: SourceRepository = fakeSourceRepo(oreillySource),
        gate: WebSourceItemGate = mockk(relaxed = true) {
            coEvery { openItem(any(), any(), any(), any()) } returns WebSourceItemGate.Outcome.Fresh
        },
        upserter: WebSourceLibraryItemUpserter = mockk(relaxed = true),
        libraryFilterPreferencesStore: LibraryFilterPreferencesStore = FakeLibraryFilterPreferencesStore(),
        catalog: Catalog = mockk<Catalog>(relaxed = true).also {
            coEvery { it.browse(any(), any(), any(), any(), any()) } returns emptyList()
        },
    ): Pair<OReillyBrowseViewModel, WebSourceItemGate> {
        val registry = mockk<CatalogRegistry>().also { coEvery { it.forSource(any()) } returns catalog }
        val handle = SavedStateHandle(mapOf("libraryId" to OReillyCatalog.ROOT_BOOKS))
        val vm = OReillyBrowseViewModel(
            handle,
            sourceRepo,
            registry,
            upserter,
            gate,
            fakeCoverGridDensityStore(),
            libraryFilterPreferencesStore,
            emptyLibraryObserver(),
            FakeConnectivityObserver(),
            TestDispatcherProvider(dispatcher),
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
            gate.openItem(sourceId = "oreilly-1", listing = item, catalog = any(), forceRefresh = false)
        }
        assertEquals("9781492032649", emitted.await().itemId)
    }

    @Test
    fun `openDetail with no active O'Reilly source no-ops`() = runTest(dispatcher) {
        val absSource = oreillySource.copy(id = "abs-1", type = SourceType.ABS)
        val (vm, gate) = makeVm(sourceRepo = fakeSourceRepo(absSource))
        advanceUntilIdle()

        vm.openDetail(sampleItem())
        advanceUntilIdle()

        coVerify(exactly = 0) { gate.openItem(any(), any(), any(), any()) }
    }

    private fun fakeSourceRepo(active: Source?): SourceRepository = object : SourceRepository {
        override fun observeAll() = flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource,
            hiddenLibraryIds: Set<String>,
        ) = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeCoverGridDensityStore(): CoverGridDensityStore =
        object : CoverGridDensityStore {
            override val scale = flowOf(1f)
            override suspend fun setScale(value: Float) = Unit
            override fun scale(sourceId: String, libraryId: String, bucket: com.riffle.core.models.ScreenDimensionBucket) = flowOf(1f)
            override suspend fun setScale(sourceId: String, libraryId: String, bucket: com.riffle.core.models.ScreenDimensionBucket, value: Float) = Unit
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
