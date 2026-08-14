package com.riffle.app.feature.source.chitanka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import com.riffle.app.ui.theme.RiffleIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.annotations.AnnotationsListScreen
import com.riffle.app.feature.annotations.AnnotationsListViewModel
import com.riffle.app.feature.library.HomeTabContent
import com.riffle.app.feature.library.LocalCoversAreSquare
import com.riffle.app.feature.library.ToReadTabContent
import com.riffle.app.feature.source.websource.UnboundedCatalogGrid
import com.riffle.app.feature.source.websource.UnboundedCoverGridZoomProvider
import com.riffle.app.feature.source.websource.WebSourceCatalogItemCard
import com.riffle.app.ui.TabletContentWidthContainer
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
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()

    val visibility by hiltViewModel<com.riffle.app.feature.library.LibraryTabVisibilityViewModel>()
        .visibility.collectAsState()
    // Annotations are anchored to ebook text — Gramofonche (the audiobook root) can never surface
    // any, so hide the tab there on top of the generic emptiness gate.
    val annotationsTabVisible = visibility.annotations && !isAudioRoot

    // Clamp if a rememberSaveable-restored selectedTab lands on a tab that is currently hidden —
    // either the audiobook root (Annotations always hidden there) or an empty To Read / Annotations
    // list. Matches the LibraryTabBar clamp on the ABS/Komga side.
    LaunchedEffect(visibility.toRead, annotationsTabVisible) {
        val hidden = when (selectedTab) {
            TAB_TO_READ -> !visibility.toRead
            TAB_ANNOTATIONS -> !annotationsTabVisible
            else -> false
        }
        if (hidden) selectedTab = TAB_HOME
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
                        IconButton(onClick = {
                            searchOpen = com.riffle.app.feature.source.common.toggleSearchOpen(searchOpen) {
                                viewModel.onQueryChange("")
                            }
                        }) {
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
                if (visibility.toRead) {
                    NavigationBarItem(
                        selected = selectedTab == TAB_TO_READ,
                        onClick = { selectedTab = TAB_TO_READ },
                        icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = "To Read") },
                    )
                }
                // Annotations are anchored to ebook text — Gramofonche (the audiobook root) can
                // never surface any, and an empty list makes the tab dead UI either way.
                if (annotationsTabVisible) {
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
            UnboundedCoverGridZoomProvider(
                persistedScale = persistedCoverScale,
                onPersistScaleChange = viewModel::setCoverGridScale,
            ) { onCoverScaleChange ->
                CompositionLocalProvider(LocalCoversAreSquare provides isAudioRoot) {
                    when (selectedTab) {
                        TAB_HOME -> ChitankaHomeTab(
                            onOpenDetail = onOpenDetail,
                            onCoverScaleChange = onCoverScaleChange,
                        )
                        TAB_TO_READ -> ChitankaToReadTab(
                            onOpenDetail = onOpenDetail,
                            onCoverScaleChange = onCoverScaleChange,
                        )
                        TAB_ANNOTATIONS ->
                            ChitankaAnnotationsTab(onAnnotatedBookClick = onAnnotatedBookClick)
                        TAB_LIBRARY -> LibraryTabContent(
                            viewModel = viewModel,
                            isAudioRoot = isAudioRoot,
                            searchOpen = searchOpen,
                            onCoverScaleChange = onCoverScaleChange,
                        )
                    }
                }
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_TO_READ = 1
private const val TAB_ANNOTATIONS = 2
private const val TAB_LIBRARY = 3

@Composable
private fun LibraryTabContent(
    viewModel: ChitankaBrowseViewModel,
    isAudioRoot: Boolean,
    searchOpen: Boolean,
    onCoverScaleChange: (Float) -> Unit,
) {
    val items by viewModel.filteredItems.collectAsState()
    val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()
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
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = notStartedFilterActive,
                    onClick = { viewModel.toggleNotStartedFilter() },
                    label = { Text("Not Started") },
                    leadingIcon = if (notStartedFilterActive) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            }
            if (facets.isNotEmpty()) {
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
                    UnboundedCatalogGrid(
                        items = items,
                        isPaging = isPaging,
                        hasMore = hasMore,
                        onLoadMore = viewModel::loadMore,
                        onCoverScaleChange = onCoverScaleChange,
                        itemKey = { it.id },
                        coverCellSizeMultiplier = if (isAudioRoot) 4f / 3f else 1f,
                    ) { item ->
                        WebSourceCatalogItemCard(
                            item = item,
                            isAudio = isAudioRoot,
                            onClick = { viewModel.openDetail(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChitankaHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
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
        onCoverScaleChange = onCoverScaleChange,
    )
}

@Composable
private fun ChitankaToReadTab(
    onOpenDetail: (itemId: String) -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    viewModel: ChitankaLibraryViewModel = hiltViewModel(),
) {
    val items by viewModel.toReadItems.collectAsState()
    ToReadTabContent(
        items = items,
        isLoading = false,
        token = "",
        onItemSelected = { item -> onOpenDetail(item.id) },
        onCoverScaleChange = onCoverScaleChange,
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
