package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.radioes.RadioEsCatalog
import com.riffle.core.catalog.radioes.radioEsFriendlyErrorMessage
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
 * ViewModel for the radio.es browse surface. See [UnboundedBrowseViewModel] for the shared
 * facet / query / pagination / open-detail state machine.
 *
 * Lives in `feature:source-ui` (android + iOS) rather than `:app` so both hosts drive the same
 * instance: `:app`'s `RadioEsBrowseScreen` and `:shared`'s `UnboundedBrowseScreen`.
 */
class RadioEsBrowseViewModel constructor(
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
    sourceType = SourceType.RADIO_ES,
    defaultRootId = RadioEsCatalog.ROOT_PODCASTS,
    pageSize = 20,
    friendlyError = ::radioEsFriendlyErrorMessage,
    dispatchers = dispatchers,
)
