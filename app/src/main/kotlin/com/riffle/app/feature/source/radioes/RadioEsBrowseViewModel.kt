package com.riffle.app.feature.source.radioes

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.source.websource.UnboundedBrowseViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.radioes.RadioEsCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import java.io.IOException
import java.net.UnknownHostException

class RadioEsBrowseViewModel constructor(
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
    sourceType = SourceType.RADIO_ES,
    defaultRootId = RadioEsCatalog.ROOT_PODCASTS,
    pageSize = 20,
    friendlyError = ::radioEsFriendlyErrorMessage,
)

internal fun radioEsFriendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach radio.es. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
