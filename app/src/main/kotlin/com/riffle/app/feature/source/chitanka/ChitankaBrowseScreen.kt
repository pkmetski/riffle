package com.riffle.app.feature.source.chitanka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import com.riffle.app.ui.theme.RiffleIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.riffle.app.feature.annotations.AnnotationsListScreen
import com.riffle.app.feature.annotations.AnnotationsListViewModel
import com.riffle.app.feature.library.HomeTabContent
import com.riffle.app.feature.library.LocalCoversAreSquare
import com.riffle.app.feature.library.ToReadTabContent
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.chitanka.ChitankaCatalog

/**
 * Chitanka Source screen. Dedicated route ("chitanka_browse/{libraryId}/{name}") distinct
 * from LibraryItemsScreen — Chitanka has no ABS-shape library mirror, so we can't reuse
 * that screen's refresh/capability plumbing (ADR 0041/0042). Instead we host a small tab
 * bar with three surfaces that ARE consistent with every other Source:
 *
 * Tabs match [LibraryItemsScreen]'s bar exactly (same icons, same order, icon-only):
 *
 * - **Home** (default) — Room-backed shelves (In Progress / Recently Added / Finished /
 *   Continue Series), fed by [ChitankaLibraryViewModel] and rendered with the same
 *   `HomeTabContent` composable ABS libraries use. Empty until the user has engaged.
 * - **Annotations** — the standard [AnnotationsListScreen], scoped to this library.
 * - **Library** — Chitanka's unbounded catalogue via [ChitankaBrowseViewModel]: search,
 *   server-side facet chips, cover grid. Tapping a card upserts the item into
 *   `library_items` and navigates to the standard `library_item_detail` page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChitankaBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: ChitankaBrowseViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable(key = "chitanka_selected_tab_v2") { mutableIntStateOf(TAB_HOME) }

    // Chitanka items don't live in `library_items` until this point (ADR 0042: unbounded
    // catalogue), so the VM upserts a row first and only then emits — guaranteeing the
    // detail screen's `LibraryObserver.getItem` resolves it.
    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    val isAudioRoot = viewModel.rootId == ChitankaCatalog.ROOT_AUDIOBOOKS
    var searchOpen by remember { mutableStateOf(false) }

    // Clamp if a rememberSaveable-restored selectedTab lands on Annotations after process
    // death when we're now on the audiobook root (where that tab is hidden).
    LaunchedEffect(isAudioRoot) {
        if (isAudioRoot && selectedTab == TAB_ANNOTATIONS) selectedTab = TAB_HOME
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(libraryName) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open drawer")
                    }
                },
                actions = {
                    if (selectedTab == TAB_LIBRARY) {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(
                                if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (searchOpen) "Close search" else "Search",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_HOME,
                    onClick = { selectedTab = TAB_HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_TO_READ,
                    onClick = { selectedTab = TAB_TO_READ },
                    icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = "To Read") },
                )
                // Annotations are anchored to ebook text — Gramofonche (the audiobook root)
                // can never surface any. Hide the tab there so it isn't dead UI.
                if (!isAudioRoot) {
                    NavigationBarItem(
                        selected = selectedTab == TAB_ANNOTATIONS,
                        onClick = { selectedTab = TAB_ANNOTATIONS },
                        icon = { Icon(RiffleIcons.Annotations, contentDescription = "Annotations") },
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == TAB_LIBRARY,
                    onClick = { selectedTab = TAB_LIBRARY },
                    icon = { Icon(Icons.Filled.GridView, contentDescription = "All Books") },
                )
            }
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            CompositionLocalProvider(LocalCoversAreSquare provides isAudioRoot) {
                when (selectedTab) {
                    TAB_HOME -> ChitankaHomeTab(onOpenDetail = onOpenDetail)
                    TAB_TO_READ -> ChitankaToReadTab(onOpenDetail = onOpenDetail)
                    TAB_ANNOTATIONS -> ChitankaAnnotationsTab(onAnnotatedBookClick = onAnnotatedBookClick)
                    TAB_LIBRARY -> LibraryTabContent(
                        viewModel = viewModel,
                        isAudioRoot = isAudioRoot,
                        searchOpen = searchOpen,
                    )
                }
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_TO_READ = 1
private const val TAB_ANNOTATIONS = 2
private const val TAB_LIBRARY = 3

// Kick off a next-page fetch when the user scrolls to within this many items of the end so the
// grid stays populated ahead of the viewport (≈ two rows in Books mode, three rows in Audiobooks).
private const val PAGINATION_PREFETCH_THRESHOLD = 6

@Composable
private fun LibraryTabContent(
    viewModel: ChitankaBrowseViewModel,
    isAudioRoot: Boolean,
    searchOpen: Boolean,
) {
    val items by viewModel.items.collectAsState()
    val facets by viewModel.facets.collectAsState()
    val selectedFacet by viewModel.selectedFacet.collectAsState()
    val query by viewModel.query.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isPaging by viewModel.isPaging.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search Chitanka…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
        if (facets.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedFacet == null,
                        onClick = { viewModel.selectFacet(null) },
                        label = { Text("All") },
                    )
                }
                items(facets, key = { it.key }) { facet ->
                    FilterChip(
                        selected = selectedFacet == facet.key,
                        onClick = { viewModel.selectFacet(facet.key) },
                        label = { Text(facet.label) },
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && items.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.wrapContentSize().align(Alignment.Center))
                }
                error != null && items.isEmpty() -> {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                items.isEmpty() -> {
                    Text(
                        if (query.isNotBlank()) "No results" else "Nothing to show",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                else -> {
                    val gridState = rememberLazyGridState()
                    // Trigger a next-page fetch once the user scrolls to within ~6 items of the
                    // end. `derivedStateOf` collapses re-emissions from `firstVisibleItem*` down
                    // to a single boolean so the LaunchedEffect below only re-fires on the actual
                    // threshold crossing. `distinctUntilChanged` further protects against Compose
                    // recomposing the same true value repeatedly.
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val info = gridState.layoutInfo
                            val total = info.totalItemsCount
                            if (total == 0) return@derivedStateOf false
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= total - PAGINATION_PREFETCH_THRESHOLD
                        }
                    }
                    LaunchedEffect(gridState, hasMore) {
                        snapshotFlow { shouldLoadMore }.distinctUntilChanged().collect { should ->
                            if (should && hasMore) viewModel.loadMore()
                        }
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(if (isAudioRoot) 2 else 3),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            CatalogItemCard(
                                item = item,
                                isAudio = isAudioRoot,
                                onClick = { viewModel.openDetail(item) },
                            )
                        }
                        // Footer spinner while the next page is fetching. Spans all columns so the
                        // grid layout doesn't leave one item's worth of empty space beside it.
                        if (isPaging) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChitankaHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
    viewModel: ChitankaLibraryViewModel = hiltViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()

    // Chitanka doesn't authenticate; cover images are public URLs — pass empty token.
    // "See more" navigates to library_section, which is ABS-shaped and wouldn't work here;
    // wire it to a no-op for now so users just scroll the horizontal shelf.
    HomeTabContent(
        inProgress = inProgress,
        continueSeries = continueSeries,
        recentlyAdded = recentlyAdded,
        finished = finished,
        isLoading = false,
        token = "",
        onItemSelected = { item -> onOpenDetail(item.id) },
        onSectionSeeMore = {},
    )
}

@Composable
private fun ChitankaToReadTab(
    onOpenDetail: (itemId: String) -> Unit,
    viewModel: ChitankaLibraryViewModel = hiltViewModel(),
) {
    val items by viewModel.toReadItems.collectAsState()
    ToReadTabContent(
        items = items,
        isLoading = false,
        token = "",
        onItemSelected = { item -> onOpenDetail(item.id) },
    )
}

@Composable
private fun ChitankaAnnotationsTab(
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: AnnotationsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    AnnotationsListScreen(
        state = state,
        token = viewModel.authToken,
        onBookClick = onAnnotatedBookClick,
    )
}

@Composable
private fun CatalogItemCard(
    item: CatalogItem,
    isAudio: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ratio = if (isAudio) 1f else (2f / 3f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!item.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    if (isAudio) "🎧" else "📖",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.author.isNotBlank()) {
            Text(
                item.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
