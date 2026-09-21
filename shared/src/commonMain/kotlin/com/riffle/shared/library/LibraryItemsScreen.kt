package com.riffle.shared.library

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.CoverGridLayout
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibraryProjection
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.LibraryTabVisibility
import com.riffle.feature.library.shouldClampSelectedTab
import com.riffle.feature.library.tabIndexForAnnotations
import com.riffle.feature.library.tabIndexForPlaylists
import com.riffle.feature.library.ui.PlaylistLabels
import com.riffle.feature.library.ui.PlaylistsTabContent
import com.riffle.feature.source.ui.DefaultCoverPlaceholder
import com.riffle.feature.source.ui.LocalCoverGridScale
import com.riffle.shared.SharedUiIcons
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val SECTION_ROW_HEIGHT = 200
private const val SECTION_CELL_WIDTH = 120

/**
 * Minimum adaptive cell for the full-page cover grids, indexed on the window width per ADR 0019
 * exactly as Android's `coverGridMinCellSize()` is. Both read the numbers from
 * [CoverGridLayout], so a tablet-sized iPad lays its covers out on the same breakpoint an Android
 * tablet does instead of staying on the phone-sized cell.
 */
@Composable
internal fun coverGridMinCell(): Dp {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    // The second argument is the user's pinch multiplier, published by CoverGridZoomBox. It used
    // to be a hardcoded 1f, which is why the persisted cover-grid density never reached a grid.
    return CoverGridLayout.minCellSizeDp(widthDp.value, LocalCoverGridScale.current).dp
}

// The tab index vocabulary and the visibility/clamp rules come from
// `com.riffle.feature.library.LibraryTabs`, which Android's LibraryItemsScreen calls too. They used
// to exist as a byte-identical private copy in each screen.

@Composable
fun LibraryItemsScreen(
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotatedBookSelected: (sourceId: String, itemId: String) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (com.riffle.core.models.Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onPlaylistSelected: (CatalogPlaylist) -> Unit,
    onSearchAnnotations: (String) -> Unit,
    viewModel: LibraryItemsViewModel = koinInject { parametersOf(libraryId) },
    // Same view model Android's Annotations tab resolves (app/.../LibraryItemsScreen.kt) and the
    // same query tab *visibility* is computed from, so the tab can never be visible-but-empty.
    annotationsViewModel: AnnotationsListViewModel = koinInject { parametersOf(libraryId) },
) {
    LaunchedEffect(libraryId) {
        viewModel.onScreenResumed()
    }

    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    SideEffect {
        viewModel.setScreenDimensionBucket(
            com.riffle.core.models.ScreenDimensionBucket.PhonePortrait.copy(
                wider = if (containerWidthPx > 1400) {
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Drive the grids off a local live scale so a pinch reflows instantly; the ViewModel debounces
    // the persist and re-emits the settled value. Same shape as Android's LibraryItemsScreen.
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()
    var liveCoverScale by remember { mutableFloatStateOf(persistedCoverScale) }
    LaunchedEffect(persistedCoverScale) { liveCoverScale = persistedCoverScale }

    // Clamp to Home when the previously-selected tab's data has disappeared.
    LaunchedEffect(tabVisibility) {
        if (shouldClampSelectedTab("", tabVisibility, selectedTab)) selectedTab = 0
    }

    Scaffold(
        topBar = {
            LibraryTopBar(title = libraryName, onMenuClick = onOpenDrawer)
        },
        bottomBar = {
            LibraryTabBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                visibility = tabVisibility ?: LibraryTabVisibility.All,
            )
        },
    ) { innerPadding ->
        CoverGridZoomBox(
            scale = liveCoverScale,
            onScaleChange = { scale ->
                liveCoverScale = scale
                viewModel.setCoverGridScale(scale)
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            } else {
                LibraryTabContent(
                    selectedTab = selectedTab,
                    projection = projection,
                    playlists = playlists,
                    annotationsState = annotationsState,
                    coversAreSquare = coversAreSquare,
                    linkedItemIds = linkedItemIds,
                    onItemSelected = onItemSelected,
                    onAnnotatedBookSelected = onAnnotatedBookSelected,
                    onSeriesSelected = onSeriesSelected,
                    onCollectionSelected = onCollectionSelected,
                    onSectionSeeMore = onSectionSeeMore,
                    onPlaylistSelected = onPlaylistSelected,
                    onSearchAnnotations = onSearchAnnotations,
                )
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
internal fun LibraryTabContent(
    selectedTab: Int,
    projection: LibraryProjection,
    playlists: List<CatalogPlaylist>,
    annotationsState: AnnotationsListUiState,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    onItemSelected: (LibraryItem) -> Unit,
    onAnnotatedBookSelected: (sourceId: String, itemId: String) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onPlaylistSelected: (CatalogPlaylist) -> Unit,
    onSearchAnnotations: (String) -> Unit,
) {
    when (selectedTab) {
        0 -> HomeTabContent(projection, coversAreSquare, linkedItemIds, onItemSelected, onSeriesSelected, onCollectionSelected, onSectionSeeMore)
        1 -> SimpleItemList(projection.toRead, "Nothing in To Read", onItemSelected)
        // The search field above the list is iOS's only route into the annotation-search
        // results screen: Android reaches it from the library search bar's "Show all"
        // affordance, which iOS has no equivalent of (#1072 §3). Without it
        // `AnnotationSearchViewModel` would be bound and unreachable.
        tabIndexForAnnotations() -> Column(Modifier.fillMaxSize()) {
            AnnotationSearchField(onSearch = onSearchAnnotations)
            AnnotationsTabContent(annotationsState, onAnnotatedBookSelected)
        }
        3 -> SeriesTabContent(projection.series, onSeriesSelected)
        4 -> CollectionsTabContent(projection.collections, onCollectionSelected)
        5 -> AllBooksTabContent(projection.allBooks, coversAreSquare, linkedItemIds, onItemSelected)
        // Index 6 previously fell through to `else`, so the Playlists tab silently rendered the
        // Home tab. The shared ViewModel has exposed `playlists` all along
        // (LibraryItemsViewModel.kt:201); the iOS screen just never read it. The tab body is now
        // :feature:library-ui's — the same one Android renders — so the two hosts cannot drift on
        // the row layout or the item-count wording, and tapping a row drills in rather than
        // dead-ending on a non-interactive list.
        tabIndexForPlaylists() -> PlaylistsTabContent(
            playlists = playlists,
            labels = PlaylistLabels.English,
            onPlaylistSelected = onPlaylistSelected,
        )
        else -> HomeTabContent(projection, coversAreSquare, linkedItemIds, onItemSelected, onSeriesSelected, onCollectionSelected, onSectionSeeMore)
    }
}

@Composable
private fun LibraryTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    visibility: LibraryTabVisibility,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(SharedUiIcons.Home, contentDescription = "Home") },
        )
        if (visibility.toRead) {
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Icon(SharedUiIcons.Bookmarks, contentDescription = "To Read") },
            )
        }
        if (visibility.annotations) {
            NavigationBarItem(
                selected = selectedTab == tabIndexForAnnotations(),
                onClick = { onTabSelected(tabIndexForAnnotations()) },
                icon = { Icon(SharedUiIcons.Star, contentDescription = "Annotations") },
            )
        }
        if (visibility.series) {
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { onTabSelected(3) },
                icon = { Icon(SharedUiIcons.FormatListNumbered, contentDescription = "Series") },
            )
        }
        if (visibility.collections) {
            NavigationBarItem(
                selected = selectedTab == 4,
                onClick = { onTabSelected(4) },
                icon = { Icon(SharedUiIcons.Folder, contentDescription = "Collections") },
            )
        }
        if (visibility.playlists) {
            NavigationBarItem(
                selected = selectedTab == tabIndexForPlaylists(),
                onClick = { onTabSelected(tabIndexForPlaylists()) },
                icon = { Icon(SharedUiIcons.QueueMusic, contentDescription = "Playlists") },
            )
        }
        NavigationBarItem(
            selected = selectedTab == 5,
            onClick = { onTabSelected(5) },
            icon = { Icon(SharedUiIcons.GridView, contentDescription = "All Books") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(title: String, onMenuClick: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(SharedUiIcons.Menu, contentDescription = "Open menu")
            }
        },
    )
}

@Composable
private fun HomeTabContent(
    projection: LibraryProjection,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    onItemSelected: (LibraryItem) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (projection.inProgress.isNotEmpty()) {
            item { SectionHeader("In Progress") { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) } }
            item { HorizontalBookRow(items = projection.inProgress.take(10), linkedItemIds = linkedItemIds, onItemClick = onItemSelected) }
        }
        if (projection.continueSeries.isNotEmpty()) {
            item { SectionHeader("Continue Series") { onSectionSeeMore(LibrarySectionType.CONTINUE_SERIES) } }
            item { HorizontalBookRow(items = projection.continueSeries.take(10), linkedItemIds = linkedItemIds, onItemClick = onItemSelected) }
        }
        if (projection.recentlyAdded.isNotEmpty()) {
            item { SectionHeader("Recently Added") { onSectionSeeMore(LibrarySectionType.RECENTLY_ADDED) } }
            item { HorizontalBookRow(items = projection.recentlyAdded.take(10), linkedItemIds = linkedItemIds, onItemClick = onItemSelected) }
        }
        if (projection.finished.isNotEmpty()) {
            item { SectionHeader("Completed") { onSectionSeeMore(LibrarySectionType.FINISHED) } }
            item { HorizontalBookRow(items = projection.finished.take(10), linkedItemIds = linkedItemIds, onItemClick = onItemSelected) }
        }
        if (projection.series.isNotEmpty()) {
            item { SectionHeader("Series", onSeeAll = null) }
            item { SeriesRow(series = projection.series.take(10), onSeriesClick = onSeriesSelected) }
        }
        if (projection.collections.isNotEmpty()) {
            item { SectionHeader("Collections", onSeeAll = null) }
            item { CollectionRow(collections = projection.collections.take(10), onCollectionClick = onCollectionSelected) }
        }
        if (projection.allBooks.isNotEmpty()) {
            item { SectionHeader("All Books", onSeeAll = null) }
            item { BookGrid(items = projection.allBooks, coversAreSquare = coversAreSquare, linkedItemIds = linkedItemIds, onItemClick = onItemSelected) }
        }
    }
}

@Composable
private fun SimpleItemList(
    items: List<LibraryItem>,
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
                    DefaultCoverPlaceholder(isAudiobook = item.isAudiobookOnly, modifier = Modifier.fillMaxSize())
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

/** Empty-state copy for the Annotations tab. Mirrors Android's `ui_no_highlights_yet`. */
internal const val ANNOTATIONS_EMPTY_LABEL = "No highlights yet."

/**
 * Grid of books with at least one live highlight, mirroring Android's `AnnotationsListScreen`.
 * [state] comes from `AnnotationsListViewModel`; tapping a book opens its detail sheet, which is
 * what Android's `onBookClick(sourceId, itemId)` does.
 */
@Composable
internal fun AnnotationsTabContent(
    state: AnnotationsListUiState,
    onBookSelected: (sourceId: String, itemId: String) -> Unit,
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (state.books.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(ANNOTATIONS_EMPTY_LABEL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        items(state.books, key = { "${it.sourceId}_${it.itemId}" }) { book ->
            AnnotatedBookTile(book = book, onClick = { onBookSelected(book.sourceId, book.itemId) })
        }
    }
}

/** Placeholder copy of the annotation search field, matched on by the iOS harness. */
internal const val ANNOTATION_SEARCH_PLACEHOLDER = "Search annotations"

/** The submit button's label. */
internal const val ANNOTATION_SEARCH_ACTION = "Search"

/**
 * The Annotations tab's search box.
 *
 * Submitting a non-blank query opens the shared annotation-search results screen. A blank query
 * does nothing rather than opening a results screen that can only say "no annotations for """ —
 * the ViewModel itself short-circuits a blank query, so an empty submit would be a dead end.
 */
@Composable
private fun AnnotationSearchField(onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(ANNOTATION_SEARCH_PLACEHOLDER) },
        trailingIcon = {
            TextButton(
                onClick = { if (query.isNotBlank()) onSearch(query) },
                modifier = Modifier
                    .semantics { contentDescription = ANNOTATION_SEARCH_PLACEHOLDER }
                    .testTag("annotation-search-submit"),
            ) {
                Text(ANNOTATION_SEARCH_ACTION)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("annotation-search-field"),
    )
}

/** One annotated book: placeholder cover, highlight-count badge, title and author. */
@Composable
internal fun AnnotatedBookTile(book: AnnotatedBook, onClick: () -> Unit) {
    val title = book.title ?: book.itemId
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            DefaultCoverPlaceholder(isAudiobook = false, modifier = Modifier.fillMaxSize())
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
private fun SeriesTabContent(series: List<Series>, onSeriesSelected: (Series) -> Unit) {
    if (series.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No series", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(series, key = { it.id }) { s ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSeriesSelected(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CollectionsTabContent(collections: List<Collection>, onCollectionSelected: (Collection) -> Unit) {
    if (collections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No collections", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(collections, key = { it.id }) { col ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onCollectionSelected(col) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(col.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AllBooksTabContent(
    items: List<LibraryItem>,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No books", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val aspect = if (coversAreSquare) 1f else 2f / 3f
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                modifier = Modifier.aspectRatio(aspect),
                onClick = { onItemSelected(item) },
                hasReadaloudLink = item.id in linkedItemIds,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (onSeeAll != null) {
            Text(
                text = "See all",
                modifier = Modifier.clickable(onClick = onSeeAll),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HorizontalBookRow(
    items: List<LibraryItem>,
    linkedItemIds: Set<String>,
    onItemClick: (LibraryItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onItemClick(item) },
                hasReadaloudLink = item.id in linkedItemIds,
            )
        }
    }
}

@Composable
private fun BookGrid(
    items: List<LibraryItem>,
    coversAreSquare: Boolean,
    linkedItemIds: Set<String>,
    onItemClick: (LibraryItem) -> Unit,
) {
    val aspect = if (coversAreSquare) 1f else 2f / 3f
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCell()),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(600.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BookCoverTile(
                item = item,
                modifier = Modifier.aspectRatio(aspect),
                onClick = { onItemClick(item) },
                hasReadaloudLink = item.id in linkedItemIds,
            )
        }
    }
}

/**
 * Accessibility label for the readaloud badge. Mirrors Android's
 * `R.string.ui_has_readaloud_synced_narration`; the iOS harness matches on it.
 */
internal const val READALOUD_BADGE_CONTENT_DESCRIPTION = "Has readaloud (synced narration)"

@Composable
fun BookCoverTile(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    hasReadaloudLink: Boolean = false,
    seriesNameBadge: String? = null,
) {
    // The cover itself keeps the merged `contentDescription = title` node every harness test
    // locates tiles by. The badges sit outside that merge so they stay individually addressable
    // (a merged node would swallow the series position text).
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) { contentDescription = item.title }
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
        ) {
            DefaultCoverPlaceholder(
                isAudiobook = item.isAudiobookOnly,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.isDownloaded || item.isCached) {
                DownloadedBadge(
                    downloaded = item.isDownloaded,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        if (hasReadaloudLink) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SharedUiIcons.Headphones,
                    contentDescription = READALOUD_BADGE_CONTENT_DESCRIPTION,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (seriesNameBadge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 5.dp, start = 5.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = seriesNameBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SeriesRow(
    series: List<Series>,
    onSeriesClick: (Series) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(series, key = { it.id }) { s ->
            SeriesTile(
                series = s,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onSeriesClick(s) },
            )
        }
    }
}

@Composable
private fun SeriesTile(
    series: Series,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = false,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = series.name,
            modifier = Modifier
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun CollectionRow(
    collections: List<Collection>,
    onCollectionClick: (Collection) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(SECTION_ROW_HEIGHT.dp),
    ) {
        items(collections, key = { it.id }) { col ->
            CollectionTile(
                collection = col,
                modifier = Modifier.width(SECTION_CELL_WIDTH.dp),
                onClick = { onCollectionClick(col) },
            )
        }
    }
}

@Composable
private fun CollectionTile(
    collection: Collection,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = collection.name }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = false,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = collection.name,
            modifier = Modifier
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
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
