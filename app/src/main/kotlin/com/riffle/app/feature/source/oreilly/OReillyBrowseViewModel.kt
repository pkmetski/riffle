package com.riffle.app.feature.source.oreilly

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.source.websource.UnboundedBrowseViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.oreilly.OReillyCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.catalog.oreilly.OReillyHttpException
import com.riffle.core.catalog.oreilly.oReillyFriendlyErrorMessage
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType

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
    defaultRootId = OReillyCatalog.ROOT_BOOKS,
    pageSize = 20,
    friendlyError = ::oReillyFriendlyErrorMessage,
)
