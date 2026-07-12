package com.riffle.app.feature.source.gutenberg

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.source.websource.UnboundedBrowseViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * ViewModel for [GutenbergBrowseScreen]. Delegates to [UnboundedBrowseViewModel] for the shared
 * facet / query / pagination / open-detail state machine (ADR 0044 Phase 5); this class only
 * carries Gutenberg-specific tuning — the [SourceType] guard, the default rootId, the page
 * size, and the host-specific error copy.
 */
@HiltViewModel
class GutenbergBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sourceRepository: SourceRepository,
    catalogRegistry: CatalogRegistry,
    libraryItemUpserter: WebSourceLibraryItemUpserter,
    webSourceItemGate: WebSourceItemGate,
) : UnboundedBrowseViewModel(
    savedStateHandle = savedStateHandle,
    sourceRepository = sourceRepository,
    catalogRegistry = catalogRegistry,
    libraryItemUpserter = libraryItemUpserter,
    webSourceItemGate = webSourceItemGate,
    sourceType = SourceType.GUTENBERG,
    defaultRootId = GutenbergCatalog.ROOT_BOOKS,
    // Gutendex ships 32 items per page — matching the server-side size keeps our request-page
    // aligned with a source-page and makes a short reply a reliable end-of-list signal.
    pageSize = 32,
    friendlyError = ::friendlyErrorMessage,
)

/**
 * Map network failures to messages users can act on. The raw OkHttp/DNS text
 * (`Unable to resolve host "gutendex.com": No address associated with hostname`) leaks
 * implementation and reads like a crash; offline is the by-far common cause.
 */
internal fun friendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach Project Gutenberg. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
