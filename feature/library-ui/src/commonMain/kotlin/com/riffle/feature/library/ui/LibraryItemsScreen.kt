package com.riffle.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.designsystem.BookGrid
import com.riffle.feature.designsystem.BookSectionGrid
import com.riffle.feature.designsystem.CoverImage
import com.riffle.feature.designsystem.DefaultCoverPlaceholder
import com.riffle.feature.designsystem.LocalCoversAreSquare
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ui_all_books
import com.riffle.feature.designsystem.generated.resources.ui_annotations
import com.riffle.feature.designsystem.generated.resources.ui_collections
import com.riffle.feature.designsystem.generated.resources.ui_home
import com.riffle.feature.designsystem.generated.resources.ui_playlists
import com.riffle.feature.designsystem.generated.resources.ui_section_completed
import com.riffle.feature.designsystem.generated.resources.ui_section_continue_series
import com.riffle.feature.designsystem.generated.resources.ui_section_in_progress
import com.riffle.feature.designsystem.generated.resources.ui_section_recently_added
import com.riffle.feature.designsystem.generated.resources.ui_series
import com.riffle.feature.designsystem.generated.resources.ui_to_read
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.AudiobookBookmarkSearchResult
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibraryProjection
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.LibrarySortMode
import com.riffle.feature.library.LibraryTabVisibility
import com.riffle.feature.library.shouldClampSelectedTab
import com.riffle.feature.library.tabIndexForAnnotations
import com.riffle.feature.library.tabIndexForPlaylists
import com.riffle.feature.library.ui.generated.resources.ui_all_books_count
import com.riffle.feature.library.ui.generated.resources.ui_annotations_count
import com.riffle.feature.library.ui.generated.resources.ui_books
import com.riffle.feature.library.ui.generated.resources.ui_collections_count
import com.riffle.feature.library.ui.generated.resources.ui_loading
import com.riffle.feature.library.ui.generated.resources.ui_no_collections
import com.riffle.feature.library.ui.generated.resources.ui_no_highlights_yet
import com.riffle.feature.library.ui.generated.resources.ui_no_results_for
import com.riffle.feature.library.ui.generated.resources.ui_no_series
import com.riffle.feature.library.ui.generated.resources.ui_no_unstarted_books
import com.riffle.feature.library.ui.generated.resources.ui_not_started
import com.riffle.feature.library.ui.generated.resources.ui_nothing_in_to_read
import com.riffle.feature.library.ui.generated.resources.ui_nothing_to_show_here
import com.riffle.feature.library.ui.generated.resources.ui_offline_banner
import com.riffle.feature.library.ui.generated.resources.ui_series_count
import com.riffle.feature.library.ui.generated.resources.ui_show_all_annotations
import com.riffle.feature.library.ui.generated.resources.ui_sort
import com.riffle.feature.library.ui.generated.resources.ui_sort_author_az
import com.riffle.feature.library.ui.generated.resources.ui_sort_oldest_first
import com.riffle.feature.library.ui.generated.resources.ui_sort_recently_added
import com.riffle.feature.library.ui.generated.resources.ui_sort_recently_opened
import com.riffle.feature.library.ui.generated.resources.ui_sort_title_az
import com.riffle.feature.library.ui.generated.resources.ui_sort_title_za
import com.riffle.feature.library.ui.generated.resources.ui_to_read_count
import com.riffle.feature.source.ui.SourceBrowseHeader
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import com.riffle.feature.library.ui.generated.resources.Res as LibRes

// The tab index vocabulary and the visibility/clamp rules come from
// `com.riffle.feature.library.LibraryTabs`, which Android's LibraryItemsScreen calls too. They used
// to exist as a byte-identical private copy in each screen.

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LibraryItemsScreen(
    libraryId: String,
    libraryName: String,
    actions: LibraryItemsActions,
    showRecentlyAdded: Boolean = true,
    // Whether the layered-back handler (clear search → home tab → exit) should intercept Back.
    // False by default so iOS callers, which manage their own back stack, need no change.
    // Android passes the result of libraryItemsBackEnabled() from MainScreen.
    backEnabled: Boolean = false,
    viewModel: LibraryItemsViewModel = koinInject { parametersOf(libraryId) },
    // Same view model Android's Annotations tab resolves (app/.../LibraryItemsScreen.kt) and the
    // same query tab *visibility* is computed from, so the tab can never be visible-but-empty.
    annotationsViewModel: AnnotationsListViewModel = koinInject { parametersOf(libraryId) },
) {
    val (
        onOpenDrawer, onItemSelected, onAnnotatedBookSelected, onSeriesSelected,
        onCollectionSelected, onSectionSeeMore, onPlaylistSelected,
        onAnnotationSelected, onAudiobookBookmarkSelected, onShowAllAnnotations, onNavigateBack,
    ) = actions
    LaunchedEffect(libraryId) {
        viewModel.onScreenResumed()
    }

    val containerWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    SideEffect {
        viewModel.setScreenDimensionBucket(
            com.riffle.core.models.ScreenDimensionBucket.PhonePortrait.copy(
                wider = if (containerWidthDp >= 840.dp) {
                    com.riffle.core.models.ScreenDimensionBucket.SizeClass.Expanded
                } else {
                    com.riffle.core.models.ScreenDimensionBucket.SizeClass.Medium
                },
            )
        )
    }

    val isLoading by viewModel.isLoading.collectAsState()
    val projection by viewModel.projection.collectAsState()
    val annotationsState by annotationsViewModel.state.collectAsState()
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()
    val tabVisibility by viewModel.tabVisibility.collectAsState()
    val linkedItemIds by viewModel.linkedItemIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val collectionCoverUrls by viewModel.collectionCoverUrls.collectAsState()
    val seriesCoverUrls by viewModel.seriesCoverUrls.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()
    val librarySortMode by viewModel.librarySortMode.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Clamp to Home when the previously-selected tab's data has disappeared.
    LaunchedEffect(tabVisibility) {
        if (shouldClampSelectedTab("", tabVisibility, selectedTab)) selectedTab = 0
    }

    // Layered back: clear search → reset to Home tab → exit (via onNavigateBack).
    // Disabled by default (backEnabled = false) so iOS, which manages its own back stack, is unaffected.
    BackHandler(enabled = backEnabled) {
        when {
            searchQuery.isNotBlank() -> viewModel.onSearchQueryChange("")
            selectedTab != 0 -> selectedTab = 0
            else -> onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            SourceBrowseHeader(
                sourceName = libraryName,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onOpenDrawer = onOpenDrawer,
            )
        },
        bottomBar = {
            if (searchQuery.isBlank()) {
                LibraryTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    visibility = tabVisibility ?: LibraryTabVisibility.All,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isOffline) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = stringResource(LibRes.string.ui_offline_banner),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            CoverGridZoomBox(
                browseScaleFlow = viewModel.coverGridScale,
                onPersistScaleChange = viewModel::setCoverGridScale,
                homeScaleFlow = viewModel.homeCoverGridScale,
                onPersistHomeScaleChange = viewModel::setHomeCoverGridScale,
                isHomeTab = selectedTab == 0,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(LibRes.string.ui_loading))
                    }
                } else if (searchQuery.isNotBlank()) {
                    SearchResultsContent(
                        query = searchQuery,
                        filteredSeries = projection.series,
                        filteredCollections = projection.collections,
                        filteredItems = projection.ungrouped,
                        annotations = projection.annotations,
                        audiobookBookmarks = projection.audiobookBookmarks,
                        seriesCoverUrls = seriesCoverUrls,
                        token = viewModel.authToken,
                        linkedItemIds = linkedItemIds,
                        onSeriesSelected = onSeriesSelected,
                        onCollectionSelected = onCollectionSelected,
                        onItemSelected = onItemSelected,
                        onAnnotationSelected = onAnnotationSelected,
                        onAudiobookBookmarkSelected = onAudiobookBookmarkSelected,
                        onShowAllAnnotations = { onShowAllAnnotations(searchQuery) },
                    )
                } else {
                    LibraryTabContent(
                        selectedTab = selectedTab,
                        projection = projection,
                        token = viewModel.authToken,
                        playlists = playlists,
                        annotationsState = annotationsState,
                        coversAreSquare = coversAreSquare,
                        linkedItemIds = linkedItemIds,
                        collectionCoverUrls = collectionCoverUrls,
                        showRecentlyAdded = showRecentlyAdded,
                        notStartedFilterActive = notStartedFilterActive,
                        librarySortMode = librarySortMode,
                        onItemSelected = onItemSelected,
                        onAnnotatedBookSelected = onAnnotatedBookSelected,
                        onSeriesSelected = onSeriesSelected,
                        onCollectionSelected = onCollectionSelected,
                        onSectionSeeMore = onSectionSeeMore,
                        onPlaylistSelected = onPlaylistSelected,
                        onToggleNotStarted = viewModel::toggleNotStartedFilter,
                        onSetSortMode = viewModel::setLibrarySortMode,
                    )
                }
            }
        }
    }
}

/**
 * Body of the tab at [selectedTab].
 *
 * Split out of [LibraryItemsScreen] so the per-tab data source is exercisable without a Koin graph
 * — in particular the Annotations tab, which must read [annotationsState] and **not**
 * [LibraryProjection.annotations]. The latter is the *search* projection: `LibraryFilterEngine`
 * returns an empty list for a blank query, and only Android's search results ever render it. iOS
 * has no search field, so a tab wired to it reads "No annotations" for every user whose tab button
 * is visible.
 */
@Composable
fun LibraryTabContent(
    selectedTab: Int,
    projection: LibraryProjection,
    playlists: List<CatalogPlaylist>,
    annotationsState: AnnotationsListUiState,
    coversAreSquare: Boolean,
    // The source's stored credential, for the authenticated cover fetches every tile now makes.
    // Defaulted so the tab-content tests can stay focused on the projection they exercise.
    token: String = "",
    linkedItemIds: Set<String>,
    collectionCoverUrls: Map<String, List<String>> = emptyMap(),
    showRecentlyAdded: Boolean = true,
    notStartedFilterActive: Boolean = false,
    librarySortMode: LibrarySortMode = LibrarySortMode.ADDED_DESC,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotatedBookSelected: (sourceId: String, itemId: String) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onPlaylistSelected: (CatalogPlaylist) -> Unit,
    onToggleNotStarted: () -> Unit,
    onSetSortMode: (LibrarySortMode) -> Unit,
) {
    when (selectedTab) {
        0 -> HomeTabContent(
            projection, token, coversAreSquare, linkedItemIds, showRecentlyAdded,
            onItemSelected, onSectionSeeMore,
        )
        1 -> ToReadTabContent(projection.toRead, token, coversAreSquare, linkedItemIds, onItemSelected)
        tabIndexForAnnotations() -> AnnotationsTabContent(annotationsState, { token }, onAnnotatedBookSelected)
        3 -> SeriesTabContent(projection.series, token, onSeriesSelected)
        4 -> CollectionsTabContent(projection.collections, token, collectionCoverUrls, onCollectionSelected)
        5 -> AllBooksTabContent(
            items = projection.allBooks,
            token = token,
            coversAreSquare = coversAreSquare,
            linkedItemIds = linkedItemIds,
            notStartedFilterActive = notStartedFilterActive,
            librarySortMode = librarySortMode,
            onItemSelected = onItemSelected,
            onToggleNotStarted = onToggleNotStarted,
            onSetSortMode = onSetSortMode,
        )
        // Index 6 previously fell through to `else`, so the Playlists tab silently rendered the
        // Home tab. The shared ViewModel has exposed `playlists` all along
        // (LibraryItemsViewModel.kt:201); the iOS screen just never read it. The tab body is now
        // :feature:library-ui's — the same one Android renders — so the two hosts cannot drift on
        // the row layout or the item-count wording, and tapping a row drills in rather than
        // dead-ending on a non-interactive list.
        tabIndexForPlaylists() -> PlaylistsTabContent(
            playlists = playlists,
            labels = playlistLabels(),
            onPlaylistSelected = onPlaylistSelected,
        )
        else -> HomeTabContent(
            projection, token, coversAreSquare, linkedItemIds, showRecentlyAdded,
            onItemSelected, onSectionSeeMore,
        )
    }
}

@Composable
private fun LibraryTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    visibility: LibraryTabVisibility,
) {
    val homeLabel = stringResource(Res.string.ui_home)
    val toReadLabel = stringResource(Res.string.ui_to_read)
    val annotationsLabel = stringResource(Res.string.ui_annotations)
    val seriesLabel = stringResource(Res.string.ui_series)
    val collectionsLabel = stringResource(Res.string.ui_collections)
    val playlistsLabel = stringResource(Res.string.ui_playlists)
    val allBooksLabel = stringResource(Res.string.ui_all_books)
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(RiffleIcons.Home, contentDescription = homeLabel) },
            modifier = Modifier.testTag(TestTags.LIBRARY_TAB_HOME),
        )
        if (visibility.toRead) {
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = toReadLabel) },
                modifier = Modifier.testTag(TestTags.LIBRARY_TAB_TO_READ),
            )
        }
        if (visibility.annotations) {
            NavigationBarItem(
                selected = selectedTab == tabIndexForAnnotations(),
                onClick = { onTabSelected(tabIndexForAnnotations()) },
                icon = { Icon(RiffleIcons.Annotations, contentDescription = annotationsLabel) },
                modifier = Modifier.testTag(TestTags.LIBRARY_TAB_ANNOTATIONS),
            )
        }
        if (visibility.series) {
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { onTabSelected(3) },
                icon = { Icon(RiffleIcons.FormatListNumbered, contentDescription = seriesLabel) },
                modifier = Modifier.testTag(TestTags.LIBRARY_TAB_SERIES),
            )
        }
        if (visibility.collections) {
            NavigationBarItem(
                selected = selectedTab == 4,
                onClick = { onTabSelected(4) },
                icon = { Icon(RiffleIcons.Folder, contentDescription = collectionsLabel) },
                modifier = Modifier.testTag(TestTags.LIBRARY_TAB_COLLECTIONS),
            )
        }
        if (visibility.playlists) {
            NavigationBarItem(
                selected = selectedTab == tabIndexForPlaylists(),
                onClick = { onTabSelected(tabIndexForPlaylists()) },
                icon = { Icon(RiffleIcons.QueueMusic, contentDescription = playlistsLabel) },
                modifier = Modifier.testTag(TestTags.LIBRARY_TAB_PLAYLISTS),
            )
        }
        NavigationBarItem(
            selected = selectedTab == 5,
            onClick = { onTabSelected(5) },
            icon = { Icon(RiffleIcons.GridView, contentDescription = allBooksLabel) },
            modifier = Modifier.testTag(TestTags.LIBRARY_TAB_ALL_BOOKS),
        )
    }
}

internal const val ANNOTATION_PREVIEW_CAP = 5

/**
 * Shows inline search results for books, series, collections, and annotations when the query is non-blank.
 *
 * Series, collections, and books are shown without a cap. Annotation results are capped at
 * [ANNOTATION_PREVIEW_CAP] with a "Show all" footer linking to the full results screen — the same
 * behaviour as the original Android LibraryItemsScreen before the parity refactor deleted this content.
 */
@Composable
internal fun SearchResultsContent(
    query: String,
    filteredSeries: List<Series>,
    filteredCollections: List<com.riffle.core.models.Collection>,
    filteredItems: List<LibraryItem>,
    annotations: List<AnnotationSearchResult>,
    audiobookBookmarks: List<AudiobookBookmarkSearchResult>,
    seriesCoverUrls: Map<String, String>,
    token: String,
    linkedItemIds: Set<String>,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (com.riffle.core.models.Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
    onShowAllAnnotations: () -> Unit,
) {
    val allEmpty = filteredSeries.isEmpty() &&
        filteredCollections.isEmpty() &&
        filteredItems.isEmpty() &&
        annotations.isEmpty() &&
        audiobookBookmarks.isEmpty()
    if (allEmpty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(LibRes.string.ui_no_results_for, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val labels = annotationSearchLabels()
    val totalAnnotations = annotations.size + audiobookBookmarks.size
    val halfCap = (ANNOTATION_PREVIEW_CAP + 1) / 2
    val annoPreview = annotations.take(if (audiobookBookmarks.isEmpty()) ANNOTATION_PREVIEW_CAP else halfCap)
    val bmPreview = audiobookBookmarks.take(ANNOTATION_PREVIEW_CAP - annoPreview.size)
    val seriesLabel = stringResource(Res.string.ui_series)
    val collectionsLabel = stringResource(Res.string.ui_collections)
    val booksLabel = stringResource(LibRes.string.ui_books)
    val annotationsLabel = stringResource(Res.string.ui_annotations)

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (filteredSeries.isNotEmpty()) {
            item { SectionHeader(title = seriesLabel) }
            items(filteredSeries, key = { "series_${it.id}" }) { s ->
                SearchSeriesListRow(
                    series = s,
                    coverUrl = seriesCoverUrls[s.id] ?: s.coverUrl,
                    token = token,
                    onClick = { onSeriesSelected(s) },
                )
            }
        }
        if (filteredCollections.isNotEmpty()) {
            item { SectionHeader(title = collectionsLabel) }
            items(filteredCollections, key = { "col_${it.id}" }) { col ->
                SearchCollectionListRow(collection = col, onClick = { onCollectionSelected(col) })
            }
        }
        if (filteredItems.isNotEmpty()) {
            item { SectionHeader(title = booksLabel) }
            items(filteredItems, key = { "item_${it.id}" }) { item ->
                SearchBookListRow(item = item, token = token, onClick = { onItemSelected(item) })
            }
        }
        if (totalAnnotations > 0) {
            item { SectionHeader(title = annotationsLabel) }
            items(annoPreview, key = { "ann_${it.annotation.id}" }) { result ->
                AnnotationResultRow(
                    result = result,
                    token = token,
                    labels = labels,
                    onClick = { onAnnotationSelected(result) },
                )
            }
            items(bmPreview, key = { "bm_${it.bookmark.id}" }) { result ->
                AudiobookBookmarkResultRow(
                    result = result,
                    token = token,
                    labels = labels,
                    onClick = { onAudiobookBookmarkSelected(result) },
                )
            }
            if (totalAnnotations > ANNOTATION_PREVIEW_CAP) {
                item {
                    val showAllText = stringResource(LibRes.string.ui_show_all_annotations, totalAnnotations)
                    TextButton(
                        onClick = onShowAllAnnotations,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag(TestTags.SHOW_ALL_ANNOTATIONS),
                    ) {
                        Text(showAllText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSeriesListRow(series: Series, coverUrl: String?, token: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
            CoverImage(
                url = coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = false,
                modifier = Modifier.fillMaxSize(),
                instrumentationKey = series.id,
            )
        }
        Text(series.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SearchCollectionListRow(collection: com.riffle.core.models.Collection, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(collection.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SearchBookListRow(item: LibraryItem, token: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
            CoverImage(
                url = item.coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = item.isAudiobookOnly,
                modifier = Modifier.fillMaxSize(),
                instrumentationKey = item.id,
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.author.isNotEmpty()) {
                Text(item.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToReadTabContent(
    items: List<LibraryItem>,
    token: String,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(LibRes.string.ui_nothing_in_to_read),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        SectionHeader(title = stringResource(LibRes.string.ui_to_read_count, items.size))
        CompositionLocalProvider(LocalCoversAreSquare provides coversAreSquare) {
            BookGrid(
                items = items,
                token = token,
                onItemSelected = onItemSelected,
                hasReadaloudLink = { it.id in linkedItemIds },
            )
        }
    }
}

@Composable
private fun HomeTabContent(
    projection: LibraryProjection,
    token: String,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    showRecentlyAdded: Boolean,
    onItemSelected: (LibraryItem) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
) {
    // Resolved once outside the LazyColumn: `stringResource` inside an `item { }` would re-read
    // the resource table for every recycled row.
    val inProgress = stringResource(Res.string.ui_section_in_progress)
    val continueSeries = stringResource(Res.string.ui_section_continue_series)
    val recentlyAdded = stringResource(Res.string.ui_section_recently_added)
    val completed = stringResource(Res.string.ui_section_completed)
    val allEmpty = projection.inProgress.isEmpty() &&
        projection.continueSeries.isEmpty() &&
        (!showRecentlyAdded || projection.recentlyAdded.isEmpty()) &&
        projection.finished.isEmpty()
    if (allEmpty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(LibRes.string.ui_nothing_to_show_here),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    CompositionLocalProvider(LocalCoversAreSquare provides coversAreSquare) {
        // Deliberately unkeyed items: the sections stream in from separate flows, and with keys
        // LazyListState anchors on the first visible KEY — a Series header that emitted first
        // stays put while Recently Added rows inserted above it land off-screen, so the home
        // opens scrolled past its first sections. Positional identity keeps it at the top.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (projection.inProgress.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "$inProgress (${projection.inProgress.size})",
                        tag = sectionHeaderTag(LibrarySectionType.IN_PROGRESS),
                    )
                }
                item {
                    BookSectionGrid(
                        items = projection.inProgress,
                        token = token,
                        linkedItemIds = linkedItemIds,
                        onItemSelected = onItemSelected,
                        onSeeMore = { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) },
                    )
                }
            }
            if (projection.continueSeries.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "$continueSeries (${projection.continueSeries.size})",
                        tag = sectionHeaderTag(LibrarySectionType.CONTINUE_SERIES),
                    )
                }
                item {
                    // Continue Series has no "See all" on Android either — full list shown.
                    BookSectionGrid(
                        items = projection.continueSeries,
                        token = token,
                        linkedItemIds = linkedItemIds,
                        onItemSelected = onItemSelected,
                        onSeeMore = null,
                        showSeriesBadge = true,
                    )
                }
            }
            if (showRecentlyAdded && projection.recentlyAdded.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "$recentlyAdded (${projection.recentlyAdded.size})",
                        tag = sectionHeaderTag(LibrarySectionType.RECENTLY_ADDED),
                    )
                }
                item {
                    BookSectionGrid(
                        items = projection.recentlyAdded,
                        token = token,
                        linkedItemIds = linkedItemIds,
                        onItemSelected = onItemSelected,
                        onSeeMore = { onSectionSeeMore(LibrarySectionType.RECENTLY_ADDED) },
                    )
                }
            }
            if (projection.finished.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "$completed (${projection.finished.size})",
                        tag = sectionHeaderTag(LibrarySectionType.FINISHED),
                    )
                }
                item {
                    BookSectionGrid(
                        items = projection.finished,
                        token = token,
                        linkedItemIds = linkedItemIds,
                        onItemSelected = onItemSelected,
                        onSeeMore = { onSectionSeeMore(LibrarySectionType.FINISHED) },
                    )
                }
            }
        }
    }
}

/**
 * Locale-independent `testTag` for a library section header, so the XCUITest harness can find a
 * section by identity rather than by its translated title. XCUITest reads a Compose `testTag` as
 * the element's `accessibilityIdentifier`.
 */
internal fun sectionHeaderTag(section: LibrarySectionType): String = "section-header-${section.name}"

@Composable
private fun SimpleItemList(
    items: List<LibraryItem>,
    token: String,
    emptyLabel: String,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onItemSelected(item) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
                    // contentDescription = null: the clickable Row merges its descendants and
                    // already announces the title and author beside the thumbnail.
                    CoverImage(
                        url = item.coverUrl,
                        token = token,
                        contentDescription = null,
                        isAudiobook = item.isAudiobookOnly,
                        modifier = Modifier.fillMaxSize(),
                        instrumentationKey = item.id,
                    )
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    if (item.author.isNotEmpty()) {
                        Text(item.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Grid of books with at least one live highlight, mirroring Android's `AnnotationsListScreen`.
 * [state] comes from `AnnotationsListViewModel`; tapping a book opens its detail sheet, which is
 * what Android's `onBookClick(sourceId, itemId)` does.
 */
@Composable
internal fun AnnotationsTabContent(
    state: AnnotationsListUiState,
    // sourceId -> auth token. A library's Annotations tab has one token; the cross-source Riffle
    // home screen resolves each book's own from `RiffleViewModel.authTokenMap`, which existed for
    // exactly this and had no iOS caller.
    tokenFor: (String) -> String,
    onBookSelected: (sourceId: String, itemId: String) -> Unit,
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(LibRes.string.ui_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (state.books.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(LibRes.string.ui_no_highlights_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = stringResource(LibRes.string.ui_annotations_count, state.books.size))
        }
        items(state.books, key = { "${it.sourceId}_${it.itemId}" }) { book ->
            AnnotatedBookTile(
                book = book,
                token = tokenFor(book.sourceId),
                onClick = { onBookSelected(book.sourceId, book.itemId) },
            )
        }
    }
}

/** One annotated book: placeholder cover, highlight-count badge, title and author. */
@Composable
internal fun AnnotatedBookTile(book: AnnotatedBook, token: String, onClick: () -> Unit) {
    val title = book.title ?: book.itemId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.annotatedBookTile(book.itemId))
            .semantics(mergeDescendants = true) { contentDescription = title }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            CoverImage(
                url = book.coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = false,
                modifier = Modifier.fillMaxSize(),
                instrumentationKey = book.itemId,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = book.highlightCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        val author = book.author
        if (author != null) {
            Text(author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeriesTabContent(series: List<Series>, token: String, onSeriesSelected: (Series) -> Unit) {
    if (series.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(LibRes.string.ui_no_series), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = stringResource(LibRes.string.ui_series_count, series.size))
        }
        items(series, key = { it.id }) { s ->
            SeriesGridTile(series = s, token = token, onClick = { onSeriesSelected(s) })
        }
    }
}

@Composable
private fun SeriesGridTile(series: Series, token: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = series.name }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            CoverImage(
                url = series.coverUrl,
                token = token,
                contentDescription = null,
                isAudiobook = false,
                modifier = Modifier.fillMaxSize(),
                instrumentationKind = "series",
                instrumentationKey = series.id,
            )
        }
        // Caption is outside the merge: iOS builds the label from contentDescription AND text,
        // which would turn it into "Name, Name" and break app.buttons["Name"] (see BookCoverTile).
        Text(
            text = series.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun CollectionsTabContent(
    collections: List<Collection>,
    token: String,
    coverUrls: Map<String, List<String>>,
    onCollectionSelected: (Collection) -> Unit,
) {
    if (collections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(LibRes.string.ui_no_collections),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = stringResource(LibRes.string.ui_collections_count, collections.size))
        }
        items(collections, key = { it.id }) { col ->
            CollectionGridTile(
                collection = col,
                token = token,
                coverUrls = coverUrls[col.id].orEmpty(),
                onClick = { onCollectionSelected(col) },
            )
        }
    }
}

@Composable
private fun CollectionGridTile(
    collection: Collection,
    token: String,
    coverUrls: List<String>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = collection.name }
            .clickable(onClick = onClick)
            .testTag(TestTags.collectionGridTile(collection.id)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            when {
                coverUrls.size >= 4 -> {
                    // 2×2 mosaic
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                MosaicCover(coverUrls[0], token, "${collection.id}_0")
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                MosaicCover(coverUrls[1], token, "${collection.id}_1")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                MosaicCover(coverUrls[2], token, "${collection.id}_2")
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                MosaicCover(coverUrls[3], token, "${collection.id}_3")
                            }
                        }
                    }
                }
                coverUrls.isNotEmpty() -> {
                    MosaicCover(coverUrls[0], token, collection.id)
                }
                else -> {
                    DefaultCoverPlaceholder(modifier = Modifier.fillMaxSize(), isAudiobook = false)
                }
            }
        }
        // Caption is outside the merge: iOS builds the label from contentDescription AND text,
        // which would turn it into "Name, Name" and break app.buttons["Name"] (see BookCoverTile).
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun MosaicCover(url: String, token: String, instrumentationKey: String) {
    CoverImage(
        url = url,
        token = token,
        contentDescription = null,
        isAudiobook = false,
        modifier = Modifier.fillMaxSize(),
        instrumentationKind = "collection",
        instrumentationKey = instrumentationKey,
    )
}

@Composable
private fun AllBooksTabContent(
    items: List<LibraryItem>,
    token: String,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    notStartedFilterActive: Boolean,
    librarySortMode: LibrarySortMode,
    onItemSelected: (LibraryItem) -> Unit,
    onToggleNotStarted: () -> Unit,
    onSetSortMode: (LibrarySortMode) -> Unit,
) {
    val notStartedLabel = stringResource(LibRes.string.ui_not_started)
    val noUnstartedLabel = stringResource(LibRes.string.ui_no_unstarted_books)
    val sortLabel = stringResource(LibRes.string.ui_sort, librarySortMode.label())
    var sortMenuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader(title = stringResource(LibRes.string.ui_all_books_count, items.size))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = notStartedFilterActive,
                onClick = onToggleNotStarted,
                label = { Text(notStartedLabel) },
                leadingIcon = if (notStartedFilterActive) {
                    { Icon(RiffleIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                modifier = Modifier.testTag(TestTags.LIBRARY_FILTER),
            )
            Box {
                FilterChip(
                    selected = librarySortMode != LibrarySortMode.ADDED_DESC,
                    onClick = { sortMenuExpanded = true },
                    label = { Text(sortLabel) },
                    trailingIcon = { Icon(RiffleIcons.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                    modifier = Modifier.testTag(TestTags.LIBRARY_SORT),
                )
                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                    LibrarySortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label()) },
                            onClick = {
                                onSetSortMode(mode)
                                sortMenuExpanded = false
                            },
                            leadingIcon = if (mode == librarySortMode) {
                                { Icon(RiffleIcons.Check, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (items.isEmpty()) {
            val emptyMsg = if (notStartedFilterActive) noUnstartedLabel else stringResource(LibRes.string.ui_all_books_count, 0)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            CompositionLocalProvider(LocalCoversAreSquare provides coversAreSquare) {
                BookGrid(
                    items = items,
                    token = token,
                    onItemSelected = onItemSelected,
                    hasReadaloudLink = { it.id in linkedItemIds },
                )
            }
        }
    }
}

@Composable
private fun LibrarySortMode.label(): String = when (this) {
    LibrarySortMode.ADDED_DESC -> stringResource(LibRes.string.ui_sort_recently_added)
    LibrarySortMode.ADDED_ASC -> stringResource(LibRes.string.ui_sort_oldest_first)
    LibrarySortMode.TITLE_ASC -> stringResource(LibRes.string.ui_sort_title_az)
    LibrarySortMode.TITLE_DESC -> stringResource(LibRes.string.ui_sort_title_za)
    LibrarySortMode.AUTHOR_ASC -> stringResource(LibRes.string.ui_sort_author_az)
    LibrarySortMode.RECENTLY_OPENED -> stringResource(LibRes.string.ui_sort_recently_opened)
}

@Composable
private fun DownloadedBadge(downloaded: Boolean, modifier: Modifier = Modifier) {
    val color = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
