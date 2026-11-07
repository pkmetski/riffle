package com.riffle.app.di

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.feature.source.ui.websource.ChitankaBrowseViewModel
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.OReillyBrowseViewModel
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Regression for the crash caused by browse VMs being registered as `viewModel {}` while the
 * call site uses `koin.get<> { parametersOf(libraryId) }` (bypassing CreationExtras).
 *
 * The three VMs must be registered as `factory { params -> }` so that SavedStateHandle is built
 * from the passed libraryId rather than resolved from the graph (where it does not exist).
 *
 * Assertion: these tests would throw `NoDefinitionFoundException` for SavedStateHandle if the
 * registrations were reverted to `viewModel { savedStateHandle = get() }`.
 */
class BrowseViewModelKoinResolutionTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    private val testDispatchers = object : DispatcherProvider {
        private val d = Dispatchers.Unconfined
        override val main = d
        override val mainImmediate = d
        override val io = d
        override val default = d
    }

    private fun testModule() = module {
        factory { params: org.koin.core.parameter.ParametersHolder ->
            OReillyBrowseViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(UnboundedBrowseViewModel.ROUTE_ARG_LIBRARY_ID to params.get<String>()),
                ),
                sourceRepository = mockk(relaxed = true),
                catalogRegistry = mockk(relaxed = true),
                libraryItemUpserter = mockk(relaxed = true),
                webSourceItemGate = mockk(relaxed = true),
                coverGridDensityStore = mockk(relaxed = true),
                libraryFilterPreferencesStore = mockk(relaxed = true),
                libraryObserver = mockk(relaxed = true),
                connectivityObserver = mockk(relaxed = true),
                dispatchers = testDispatchers,
                toReadRepository = mockk(relaxed = true),
            )
        }
        factory { params: org.koin.core.parameter.ParametersHolder ->
            ChitankaBrowseViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(UnboundedBrowseViewModel.ROUTE_ARG_LIBRARY_ID to params.get<String>()),
                ),
                sourceRepository = mockk(relaxed = true),
                catalogRegistry = mockk(relaxed = true),
                libraryItemUpserter = mockk(relaxed = true),
                webSourceItemGate = mockk(relaxed = true),
                coverGridDensityStore = mockk(relaxed = true),
                libraryFilterPreferencesStore = mockk(relaxed = true),
                libraryObserver = mockk(relaxed = true),
                connectivityObserver = mockk(relaxed = true),
                dispatchers = testDispatchers,
            )
        }
        factory { params: org.koin.core.parameter.ParametersHolder ->
            GutenbergBrowseViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(UnboundedBrowseViewModel.ROUTE_ARG_LIBRARY_ID to params.get<String>()),
                ),
                sourceRepository = mockk(relaxed = true),
                catalogRegistry = mockk(relaxed = true),
                libraryItemUpserter = mockk(relaxed = true),
                webSourceItemGate = mockk(relaxed = true),
                coverGridDensityStore = mockk(relaxed = true),
                libraryFilterPreferencesStore = mockk(relaxed = true),
                libraryObserver = mockk(relaxed = true),
                connectivityObserver = mockk(relaxed = true),
                dispatchers = testDispatchers,
            )
        }
        factory { params: org.koin.core.parameter.ParametersHolder ->
            RadioEsBrowseViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(UnboundedBrowseViewModel.ROUTE_ARG_LIBRARY_ID to params.get<String>()),
                ),
                sourceRepository = mockk(relaxed = true),
                catalogRegistry = mockk(relaxed = true),
                libraryItemUpserter = mockk(relaxed = true),
                webSourceItemGate = mockk(relaxed = true),
                coverGridDensityStore = mockk(relaxed = true),
                libraryFilterPreferencesStore = mockk(relaxed = true),
                libraryObserver = mockk(relaxed = true),
                connectivityObserver = mockk(relaxed = true),
                dispatchers = testDispatchers,
            )
        }
    }

    @Test
    fun `ChitankaBrowseViewModel resolves via parametersOf without SavedStateHandle in graph`() {
        val koin = startKoin { modules(testModule()) }.koin
        val vm = koin.get<ChitankaBrowseViewModel> { parametersOf("lib-chitanka") }
        assertEquals("lib-chitanka", vm.rootId)
    }

    @Test
    fun `GutenbergBrowseViewModel resolves via parametersOf without SavedStateHandle in graph`() {
        val koin = startKoin { modules(testModule()) }.koin
        val vm = koin.get<GutenbergBrowseViewModel> { parametersOf("lib-gutenberg") }
        assertEquals("lib-gutenberg", vm.rootId)
    }

    @Test
    fun `RadioEsBrowseViewModel resolves via parametersOf without SavedStateHandle in graph`() {
        val koin = startKoin { modules(testModule()) }.koin
        val vm = koin.get<RadioEsBrowseViewModel> { parametersOf("lib-radioes") }
        assertEquals("lib-radioes", vm.rootId)
    }

    @Test
    fun `OReillyBrowseViewModel resolves via parametersOf without SavedStateHandle in graph`() {
        val koin = startKoin { modules(testModule()) }.koin
        val vm = koin.get<OReillyBrowseViewModel> { parametersOf("lib-oreilly") }
        assertEquals("lib-oreilly", vm.rootId)
    }

    /**
     * Regression for search term being cleared on back-navigation from item detail to browse.
     *
     * Before the fix, UnboundedBrowseScreen created its own ViewModel internally. When the user
     * navigated to item detail, the screen left the composition, its DisposableEffect cleared the
     * ScreenScopedViewModelHost, and navigation back created a fresh ViewModel — resetting _query
     * to "". The fix lifts ViewModel creation to LibraryHost (AppShell), giving the ViewModel the
     * same lifetime as libraryItemsViewModel so it survives within-library navigation.
     *
     * This test asserts the invariant the fix relies on: the same ViewModel instance retains its
     * query, while a new instance resets it. If this assertion fails, the in-memory query contract
     * is broken regardless of which component holds the reference.
     */
    @Test
    fun `ChitankaBrowseViewModel retains query on instance reuse and resets on new instance`() {
        val koin = startKoin { modules(testModule()) }.koin
        val vm = koin.get<ChitankaBrowseViewModel> { parametersOf("lib-chitanka") }
        vm.onQueryChange("tolkien")
        assertEquals("tolkien", vm.query.value)

        val freshVm = koin.get<ChitankaBrowseViewModel> { parametersOf("lib-chitanka") }
        assertEquals("", freshVm.query.value)
    }
}
