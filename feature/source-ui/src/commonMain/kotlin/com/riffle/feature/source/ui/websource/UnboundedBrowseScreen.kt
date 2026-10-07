package com.riffle.feature.source.ui.websource

import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.models.SourceType
import com.riffle.feature.library.tabIndexForAnnotations

const val TAB_HOME = 0
const val TAB_TO_READ = 1
const val TAB_ANNOTATIONS = 2
const val TAB_LIBRARY = 3

/**
 * Maps the unbounded-browse screen's local tab index to the `LibraryTabContent` tab index that
 * serves the same content. The two index spaces differ: the local bar has only 4 slots (Home / To
 * Read / Annotations / Library) while `LibraryTabContent` handles the full ABS tab set (which
 * includes Series, Collections, Playlists). Keeping this as a named function makes it testable.
 */
fun unboundedLocalTabToLibraryTabIndex(localTab: Int): Int = when (localTab) {
    TAB_HOME -> 0
    TAB_TO_READ -> 1
    else -> tabIndexForAnnotations()
}

/**
 * Whether the root being browsed holds audio rather than books, which drives square cover art and
 * the roomier cell size. Chitanka's second root is gramofonche (audiobooks); radio.es is audio
 * throughout; Gutenberg is ebooks only. Same rule each Android browse screen applied inline.
 */
fun isAudioRoot(sourceType: SourceType, rootId: String): Boolean = when (sourceType) {
    SourceType.RADIO_ES -> true
    SourceType.CHITANKA -> rootId == ChitankaCatalog.ROOT_AUDIOBOOKS
    else -> false
}

/**
 * The unbounded-catalogue source types that can be browsed with `UnboundedBrowseScreen`
 * (defined in `:feature:library-ui`).
 *
 * Every `SourceType.isUnboundedCatalog` type except O'Reilly, which authenticates through an
 * in-app WebView login that harvests the `orm-jwt` cookie — iOS has no implementation of that, so
 * `OReillyCatalogFactory.create` would return null forever. Pinned by
 * `IosSupportedSourceTypesTest` so a new unbounded source cannot be added to `SourceType` and
 * silently skip this screen.
 */
fun unboundedBrowseSourceTypes(): Set<SourceType> =
    SourceType.entries.filter { it.isUnboundedCatalog && it != SourceType.OREILLY }.toSet()

/**
 * Whether the library host should render `UnboundedBrowseScreen` instead of `LibraryItemsScreen`.
 *
 * The iOS equivalent of Android's `NavRoutes.libraryEntryRoute` dispatch, and extracted from
 * `HomeScreen.LibraryHost` so it can be asserted without standing up a composition. A null
 * [sourceType] is the cold-start case where the active source has not resolved yet; Android falls
 * back to `library_items` there too and corrects on the next drawer selection.
 */
fun shouldRenderUnboundedBrowse(sourceType: SourceType?): Boolean =
    sourceType != null && sourceType in unboundedBrowseSourceTypes()
