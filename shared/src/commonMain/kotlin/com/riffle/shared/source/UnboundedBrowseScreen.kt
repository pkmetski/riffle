package com.riffle.shared.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.SourceBrowseHeader
import com.riffle.feature.source.ui.SourceTypeIcon
import com.riffle.feature.source.ui.websource.ChitankaBrowseViewModel
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseLibraryTabFor
import com.riffle.feature.source.ui.websource.UnboundedBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedCoverGridZoomProvider
import com.riffle.shared.ScreenScopedViewModelHost
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The iOS host's browse surface for an unbounded catalogue (`SourceType.isUnboundedCatalog`).
 *
 * These sources are network-only per ADR 0051 — nothing is mirrored into `library_items`, so
 * `IosLibraryRefresherImpl.refreshLibraryItems` returns early for them exactly as Android's
 * `LibraryRepositoryImpl` does. Before this screen existed the iOS host rendered
 * `LibraryItemsScreen` for every library with no source-type fork, so installing Chitanka,
 * Gutenberg or radio.es produced a permanently empty library with no error (#1071 §17).
 *
 * Everything below the shell is `feature:source-ui`: the search header, the filter-chip strip,
 * the zoomable grid and the item cards are the same composables Android's per-source browse
 * screens render, driven by the same `UnboundedBrowseViewModel`. Only the shell differs — Android
 * wraps its Library tab in a four-tab Scaffold (Home / To Read / Annotations / Library) that the
 * iOS library host has no equivalent of yet.
 */
@Composable
internal fun UnboundedBrowseScreen(
    sourceType: SourceType,
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
) {
    val key = "$sourceType/$libraryId"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel = remember(key) { host.adopt(unboundedBrowseViewModel(sourceType, libraryId)) }
    DisposableEffect(key) { onDispose { host.clear() } }

    // The VM upserts the tapped CatalogItem into `library_items` through WebSourceItemGate and
    // only then emits, so the detail screen's LibraryObserver.getItem can resolve it. Same
    // contract Android's browse screens collect.
    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    val query by viewModel.query.collectAsState()
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SourceBrowseHeader(
            sourceName = libraryName,
            searchQuery = query,
            onSearchQueryChange = viewModel::onQueryChange,
            onOpenDrawer = onOpenDrawer,
            // Same 24dp glyph with the same trailing gap Android's browse screens use — the
            // header is shared, so the icon slot must be filled the same way on both.
            sourceIcon = {
                SourceTypeIcon(type = sourceType, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
            },
        )
        UnboundedCoverGridZoomProvider(
            persistedScale = persistedCoverScale,
            onPersistScaleChange = viewModel::setCoverGridScale,
        ) { onCoverScaleChange ->
            UnboundedBrowseLibraryTabFor(
                sourceType = sourceType,
                viewModel = viewModel,
                onCoverScaleChange = onCoverScaleChange,
                isAudio = isAudioRoot(sourceType, viewModel.rootId),
            )
        }
    }
}

/**
 * Whether the root being browsed holds audio rather than books, which drives square cover art and
 * the roomier cell size. Chitanka's second root is gramofonche (audiobooks); radio.es is audio
 * throughout; Gutenberg is ebooks only. Same rule each Android browse screen applied inline.
 */
internal fun isAudioRoot(sourceType: SourceType, rootId: String): Boolean = when (sourceType) {
    SourceType.RADIO_ES -> true
    SourceType.CHITANKA -> rootId == ChitankaCatalog.ROOT_AUDIOBOOKS
    else -> false
}

private fun unboundedBrowseViewModel(
    sourceType: SourceType,
    libraryId: String,
): UnboundedBrowseViewModel {
    val koin = KoinPlatform.getKoin()
    return when (sourceType) {
        SourceType.CHITANKA -> koin.get<ChitankaBrowseViewModel> { parametersOf(libraryId) }
        SourceType.GUTENBERG -> koin.get<GutenbergBrowseViewModel> { parametersOf(libraryId) }
        SourceType.RADIO_ES -> koin.get<RadioEsBrowseViewModel> { parametersOf(libraryId) }
        // Unreachable: the host only routes here for a type in `unboundedBrowseSourceTypes`, and
        // `IosSupportedSourceTypesTest` pins that set against SourceType.isUnboundedCatalog.
        else -> error("No iOS browse ViewModel for $sourceType")
    }
}

/**
 * The unbounded-catalogue source types the iOS host can browse.
 *
 * Every `SourceType.isUnboundedCatalog` type except O'Reilly, which authenticates through an
 * in-app WebView login that harvests the `orm-jwt` cookie — iOS has no implementation of that, so
 * `OReillyCatalogFactory.create` would return null forever. Pinned by
 * `IosSupportedSourceTypesTest` so a new unbounded source cannot be added to `SourceType` and
 * silently skip this screen.
 */
internal fun unboundedBrowseSourceTypes(): Set<SourceType> =
    SourceType.entries.filter { it.isUnboundedCatalog && it != SourceType.OREILLY }.toSet()

/**
 * Whether the library host should render [UnboundedBrowseScreen] instead of `LibraryItemsScreen`.
 *
 * The iOS equivalent of Android's `NavRoutes.libraryEntryRoute` dispatch, and extracted from
 * `HomeScreen.LibraryHost` so it can be asserted without standing up a composition. A null
 * [sourceType] is the cold-start case where the active source has not resolved yet; Android falls
 * back to `library_items` there too and corrects on the next drawer selection.
 */
internal fun shouldRenderUnboundedBrowse(sourceType: SourceType?): Boolean =
    sourceType != null && sourceType in unboundedBrowseSourceTypes()
