package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.oreilly.OReillyRoots
import com.riffle.core.catalog.oreilly.oReillyFriendlyErrorMessage
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType

/**
 * ViewModel for the O'Reilly browse surface. Delegates to [UnboundedBrowseViewModel] for the
 * shared facet / query / pagination / open-detail state machine; this class only carries
 * O'Reilly-specific tuning — the [SourceType] guard, the default rootId, the page size, and the
 * source-specific error copy.
 *
 * Lives in `feature:source-ui` (android + iOS) rather than `:app` so both hosts drive the same
 * instance: `:app`'s `UnboundedBrowseScreen` and `:shared`'s `UnboundedBrowseScreen` construct it
 * through their own Koin graphs.
 */
class OReillyBrowseViewModel constructor(
    savedStateHandle: SavedStateHandle,
    sourceRepository: SourceRepository,
    catalogRegistry: CatalogRegistry,
    libraryItemUpserter: WebSourceLibraryItemUpserter,
    webSourceItemGate: WebSourceItemGate,
    coverGridDensityStore: CoverGridDensityStore,
    libraryFilterPreferencesStore: LibraryFilterPreferencesStore,
    libraryObserver: LibraryObserver,
    connectivityObserver: ConnectivityObserver,
    dispatchers: DispatcherProvider,
) : UnboundedBrowseViewModel(
    savedStateHandle = savedStateHandle,
    sourceRepository = sourceRepository,
    catalogRegistry = catalogRegistry,
    libraryItemUpserter = libraryItemUpserter,
    webSourceItemGate = webSourceItemGate,
    coverGridDensityStore = coverGridDensityStore,
    libraryFilterPreferencesStore = libraryFilterPreferencesStore,
    libraryObserver = libraryObserver,
    connectivityObserver = connectivityObserver,
    sourceType = SourceType.OREILLY,
    defaultRootId = OReillyRoots.BOOKS,
    pageSize = 20,
    friendlyError = ::oReillyFriendlyErrorMessage,
    dispatchers = dispatchers,
)
