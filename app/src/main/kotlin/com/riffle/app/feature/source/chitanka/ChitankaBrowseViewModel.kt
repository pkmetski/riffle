package com.riffle.app.feature.source.chitanka

import androidx.lifecycle.SavedStateHandle
import com.riffle.app.feature.source.websource.UnboundedBrowseViewModel
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.data.websource.WebSourceItemGate
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * ViewModel for [ChitankaBrowseScreen]. Delegates to [UnboundedBrowseViewModel] for the shared
 * facet / query / pagination / open-detail state machine (ADR 0044 Phase 5); this class only
 * carries Chitanka-specific tuning — the [SourceType] guard, the default rootId, the page size,
 * and the host-specific error copy.
 */
@HiltViewModel
class ChitankaBrowseViewModel @Inject constructor(
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
    sourceType = SourceType.CHITANKA,
    defaultRootId = ChitankaCatalog.ROOT_BOOKS,
    // Chitanka lists ~30 items per page in most views; 50 gives us a small safety margin so the
    // grid usually has to scroll before we page again.
    pageSize = 50,
    friendlyError = ::friendlyErrorMessage,
)

/**
 * Map network failures to messages users can act on. The raw OkHttp/DNS text
 * (`Unable to resolve host "chitanka.info": No address associated with hostname`) leaks
 * implementation and reads like a crash; offline is the by-far common cause.
 */
internal fun friendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it is IOException } ->
            "Couldn't reach chitanka.info. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
