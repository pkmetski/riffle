package com.riffle.shared.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.tabIndexForAnnotations
import com.riffle.feature.source.ui.SourceBrowseHeader
import com.riffle.feature.source.ui.SourceTypeIcon
import com.riffle.feature.source.ui.websource.ChitankaBrowseViewModel
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseLibraryTabFor
import com.riffle.feature.source.ui.websource.UnboundedBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedCoverGridZoomProvider
import com.riffle.shared.ScreenScopedViewModelHost
import com.riffle.shared.library.LibraryTabContent
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

internal const val TAB_HOME = 0
internal const val TAB_TO_READ = 1
internal const val TAB_ANNOTATIONS = 2
internal const val TAB_LIBRARY = 3

/**
 * The iOS host's browse surface for an unbounded catalogue (`SourceType.isUnboundedCatalog`).
 *
 * These sources are network-only per ADR 0051 — nothing is mirrored into `library_items`, so
 * `IosLibraryRefresherImpl.refreshLibraryItems` returns early for them exactly as Android's
 * `LibraryRepositoryImpl` does. Before this screen existed the iOS host rendered
 * `LibraryItemsScreen` for every library with no source-type fork, so installing Chitanka,
 * Gutenberg or radio.es produced a permanently empty library with no error (#1071 §17).
 *
 * The four-tab Scaffold (Home / To Read / Annotations / Library) mirrors the structure Android's
 * browse screens provide. The first three tabs are backed by `LibraryItemsViewModel` — the same
 * ViewModel driving ABS and Komga libraries — which observes items in `library_items` upserted
 * via `WebSourceItemGate` each time the user opens one. The Library tab is the unbounded-catalogue
 * browse surface from `feature:source-ui`, driven by `UnboundedBrowseViewModel`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnboundedBrowseScreen(
    sourceType: SourceType,
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onSearchAnnotations: (String) -> Unit,
) {
    val key = "$sourceType/$libraryId"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel = remember(key) { host.adopt(unboundedBrowseViewModel(sourceType, libraryId)) }
    val libraryVm: LibraryItemsViewModel = koinInject { parametersOf(libraryId) }
    val annotationsVm: AnnotationsListViewModel = koinInject { parametersOf(libraryId) }
    DisposableEffect(key) { onDispose { host.clear() } }

    // The VM upserts the tapped CatalogItem into `library_items` through WebSourceItemGate and
    // only then emits, so the detail screen's LibraryObserver.getItem can resolve it. Same
    // contract Android's browse screens collect.
    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    val query by viewModel.query.collectAsState()

    val projection by libraryVm.projection.collectAsState()
    val annotationsState by annotationsVm.state.collectAsState()
    val tabVisibility by libraryVm.tabVisibility.collectAsState()
    val playlists by libraryVm.playlists.collectAsState()
    val coversAreSquare = isAudioRoot(sourceType, viewModel.rootId)

    var selectedLocalTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }

    Scaffold(
        topBar = {
            if (selectedLocalTab == TAB_LIBRARY) {
                SourceBrowseHeader(
                    sourceName = libraryName,
                    searchQuery = query,
                    onSearchQueryChange = viewModel::onQueryChange,
                    onOpenDrawer = onOpenDrawer,
                    // Same 24dp glyph with the same trailing gap Android's browse screens use.
                    sourceIcon = {
                        SourceTypeIcon(type = sourceType, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(libraryName) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                            Icon(RiffleIcons.Menu, contentDescription = "Open menu")
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedLocalTab == TAB_HOME,
                    onClick = { selectedLocalTab = TAB_HOME },
                    icon = { Icon(RiffleIcons.Home, contentDescription = "Home") },
                )
                if (tabVisibility?.toRead == true) {
                    NavigationBarItem(
                        selected = selectedLocalTab == TAB_TO_READ,
                        onClick = { selectedLocalTab = TAB_TO_READ },
                        icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = "To Read") },
                    )
                }
                if (tabVisibility?.annotations == true) {
                    NavigationBarItem(
                        selected = selectedLocalTab == TAB_ANNOTATIONS,
                        onClick = { selectedLocalTab = TAB_ANNOTATIONS },
                        icon = { Icon(RiffleIcons.Annotations, contentDescription = "Annotations") },
                    )
                }
                NavigationBarItem(
                    selected = selectedLocalTab == TAB_LIBRARY,
                    onClick = { selectedLocalTab = TAB_LIBRARY },
                    icon = { Icon(RiffleIcons.GridView, contentDescription = "Library") },
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedLocalTab) {
                TAB_HOME, TAB_TO_READ, TAB_ANNOTATIONS -> {
                    val libraryTabIndex = unboundedLocalTabToLibraryTabIndex(selectedLocalTab)
                    UnboundedCoverGridZoomProvider(
                        viewModel = viewModel,
                        selectedTab = selectedLocalTab,
                    ) { _ ->
                        LibraryTabContent(
                            selectedTab = libraryTabIndex,
                            projection = projection,
                            playlists = playlists,
                            annotationsState = annotationsState,
                            coversAreSquare = coversAreSquare,
                            linkedItemIds = emptySet(),
                            // Web sources only have items in library_items when the user has opened
                            // them — showing a "Recently Added" section would reflect open history,
                            // not anything the source published, so we suppress it.
                            showRecentlyAdded = false,
                            onItemSelected = { item -> onOpenDetail(item.id) },
                            onAnnotatedBookSelected = { _, itemId -> onOpenDetail(itemId) },
                            onSeriesSelected = {},
                            onCollectionSelected = {},
                            onSectionSeeMore = {},
                            onPlaylistSelected = {},
                            onSearchAnnotations = onSearchAnnotations,
                        )
                    }
                }
                else -> {
                    UnboundedCoverGridZoomProvider(
                        browseScaleFlow = viewModel.coverGridScale,
                        onPersistScaleChange = viewModel::setCoverGridScale,
                    ) { onCoverScaleChange ->
                        UnboundedBrowseLibraryTabFor(
                            sourceType = sourceType,
                            viewModel = viewModel,
                            onCoverScaleChange = onCoverScaleChange,
                            isAudio = coversAreSquare,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps the unbounded-browse screen's local tab index to the `LibraryTabContent` tab index that
 * serves the same content. The two index spaces differ: the local bar has only 4 slots (Home / To
 * Read / Annotations / Library) while `LibraryTabContent` handles the full ABS tab set (which
 * includes Series, Collections, Playlists). Keeping this as a named function makes it testable.
 */
internal fun unboundedLocalTabToLibraryTabIndex(localTab: Int): Int = when (localTab) {
    TAB_HOME -> 0
    TAB_TO_READ -> 1
    else -> tabIndexForAnnotations()
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
