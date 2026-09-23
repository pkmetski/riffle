package com.riffle.feature.source.ui.websource

import androidx.lifecycle.SavedStateHandle
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.catalog.gutenberg.GutenbergHttpException
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
 * ViewModel for the Gutenberg browse surface. Delegates to [UnboundedBrowseViewModel] for the
 * shared facet / query / pagination / open-detail state machine (ADR 0053 Phase 5); this class
 * only carries Gutenberg-specific tuning — the [SourceType] guard, the default rootId, the page
 * size, and the host-specific error copy.
 *
 * Lives in `feature:source-ui` (android + iOS) rather than `:app` so both hosts drive the same
 * instance: `:app`'s `GutenbergBrowseScreen` and `:shared`'s `UnboundedBrowseScreen` construct it
 * through their own Koin graphs.
 */
class GutenbergBrowseViewModel constructor(
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
    sourceType = SourceType.GUTENBERG,
    defaultRootId = GutenbergCatalog.ROOT_BOOKS,
    // Gutendex ships 32 items per page — matching the server-side size keeps our request-page
    // aligned with a source-page and makes a short reply a reliable end-of-list signal.
    pageSize = 32,
    friendlyError = ::gutenbergFriendlyErrorMessage,
    dispatchers = dispatchers,
)

/**
 * Map network failures to messages users can act on. The raw OkHttp/DNS text
 * (`Unable to resolve host "gutendex.com": No address associated with hostname`) leaks
 * implementation and reads like a crash; offline is the by-far common cause.
 *
 * Matches on the exception's simple name rather than `is UnknownHostException` / `is IOException`
 * because those are JVM types with no `commonMain` equivalent, and this now runs on Kotlin/Native
 * too. Same shape as the already-shared `radioEsFriendlyErrorMessage` / `oReillyFriendlyErrorMessage`.
 * Darwin's Ktor engine wraps DNS failures in an `IOException` whose message carries the host, so
 * the offline branch still fires on iOS; anything else falls through to the generic copy.
 */
fun gutenbergFriendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it::class.simpleName?.contains("UnknownHostException") == true } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it::class.simpleName?.endsWith("IOException") == true } ->
            "Couldn't reach Project Gutenberg. Check your connection and try again."
        chain.any { it is GutenbergHttpException } ->
            "Couldn't reach Project Gutenberg. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
