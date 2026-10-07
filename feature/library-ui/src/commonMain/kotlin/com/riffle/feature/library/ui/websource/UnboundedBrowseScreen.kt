package com.riffle.feature.library.ui.websource

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
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.ui.CoverGridZoomBox
import com.riffle.feature.library.ui.LibraryTabContent
import com.riffle.feature.source.ui.SourceBrowseHeader
import com.riffle.feature.source.ui.SourceTypeIcon
import com.riffle.feature.source.ui.websource.ChitankaBrowseViewModel
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.TAB_ANNOTATIONS
import com.riffle.feature.source.ui.websource.TAB_HOME
import com.riffle.feature.source.ui.websource.TAB_LIBRARY
import com.riffle.feature.source.ui.websource.TAB_TO_READ
import com.riffle.feature.source.ui.websource.UnboundedBrowseLibraryTabFor
import com.riffle.feature.source.ui.websource.UnboundedBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedCoverGridZoomProvider
import com.riffle.feature.source.ui.websource.isAudioRoot
import com.riffle.feature.source.ui.websource.unboundedLocalTabToLibraryTabIndex
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * Browse surface for an unbounded catalogue (`SourceType.isUnboundedCatalog`), shared by both
 * the Android app and the iOS host.
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
 * browse surface driven by `UnboundedBrowseViewModel`.
 *
 * Lives in `:feature:library-ui` rather than `:feature:source-ui` because it depends on
 * `LibraryTabContent` and `CoverGridZoomBox` from `:feature:library-ui`, and
 * `:feature:library-ui` → `:feature:source-ui` is the existing dependency direction — putting it
 * in `:feature:source-ui` would introduce a cycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnboundedBrowseScreen(
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
                    CoverGridZoomBox(
                        browseScaleFlow = viewModel.coverGridScale,
                        onPersistScaleChange = viewModel::setCoverGridScale,
                        homeScaleFlow = viewModel.homeCoverGridScale,
                        onPersistHomeScaleChange = viewModel::setHomeCoverGridScale,
                        isHomeTab = selectedLocalTab == TAB_HOME,
                        modifier = Modifier.fillMaxSize(),
                    ) {
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
                            onItemSelected = { item: LibraryItem -> onOpenDetail(item.id) },
                            onAnnotatedBookSelected = { _: String, itemId: String -> onOpenDetail(itemId) },
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

private fun unboundedBrowseViewModel(
    sourceType: SourceType,
    libraryId: String,
): UnboundedBrowseViewModel {
    val koin = KoinPlatform.getKoin()
    return when (sourceType) {
        SourceType.CHITANKA -> koin.get<ChitankaBrowseViewModel> { parametersOf(libraryId) }
        SourceType.GUTENBERG -> koin.get<GutenbergBrowseViewModel> { parametersOf(libraryId) }
        SourceType.RADIO_ES -> koin.get<RadioEsBrowseViewModel> { parametersOf(libraryId) }
        else -> error("No browse ViewModel for $sourceType")
    }
}
