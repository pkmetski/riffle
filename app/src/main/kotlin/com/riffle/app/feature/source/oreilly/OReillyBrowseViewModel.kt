package com.riffle.app.feature.source.oreilly

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.source.websource.UnboundedBrowseViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.oreilly.OReillyCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import java.io.IOException
import java.net.UnknownHostException

class OReillyBrowseViewModel constructor(
    savedStateHandle: SavedStateHandle,
    sourceRepository: SourceRepository,
    catalogRegistry: CatalogRegistry,
    libraryItemUpserter: WebSourceLibraryItemUpserter,
    webSourceItemGate: WebSourceItemGate,
    coverGridDensityStore: CoverGridDensityStore,
    libraryFilterPreferencesStore: LibraryFilterPreferencesStore,
    libraryObserver: LibraryObserver,
) : UnboundedBrowseViewModel(
    savedStateHandle = savedStateHandle,
    sourceRepository = sourceRepository,
    catalogRegistry = catalogRegistry,
    libraryItemUpserter = libraryItemUpserter,
    webSourceItemGate = webSourceItemGate,
    coverGridDensityStore = coverGridDensityStore,
    libraryFilterPreferencesStore = libraryFilterPreferencesStore,
    libraryObserver = libraryObserver,
    sourceType = SourceType.OREILLY,
    defaultRootId = OReillyCatalog.ROOT_BOOKS,
    pageSize = 20,
    friendlyError = ::oReillyFriendlyErrorMessage,
)

internal fun oReillyFriendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach O'Reilly. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
