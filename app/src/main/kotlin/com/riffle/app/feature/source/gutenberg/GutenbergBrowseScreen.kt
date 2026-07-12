package com.riffle.app.feature.source.gutenberg

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
import com.riffle.app.feature.library.ToReadTabContent
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.catalog.CatalogItem

/**
 * Gutenberg Source screen. Distinct route ("gutenberg_browse/{libraryId}/{name}") from
 * LibraryItemsScreen — Gutenberg has no ABS-shape library mirror, so we can't reuse that
 * screen's refresh/capability plumbing. Instead we host a small tab bar with four surfaces
 * that ARE consistent with every other Source (Home / To Read / Annotations / Library),
 * mirroring the Chitanka browse screen's structure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GutenbergBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: GutenbergBrowseViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable(key = "gutenberg_selected_tab_v1") { mutableIntStateOf(TAB_HOME) }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
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
                NavigationBarItem(
                    selected = selectedTab == TAB_TO_READ,
                    onClick = { selectedTab = TAB_TO_READ },
                    icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = "To Read") },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_ANNOTATIONS,
                    onClick = { selectedTab = TAB_ANNOTATIONS },
                    icon = { Icon(RiffleIcons.Annotations, contentDescription = "Annotations") },
                )
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
            when (selectedTab) {
                TAB_HOME -> GutenbergHomeTab(onOpenDetail = onOpenDetail)
                TAB_TO_READ -> GutenbergToReadTab(onOpenDetail = onOpenDetail)
                TAB_ANNOTATIONS -> GutenbergAnnotationsTab(onAnnotatedBookClick = onAnnotatedBookClick)
                TAB_LIBRARY -> LibraryTabContent(
                    viewModel = viewModel,
                    searchOpen = searchOpen,
                )
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_TO_READ = 1
private const val TAB_ANNOTATIONS = 2
private const val TAB_LIBRARY = 3

private const val PAGINATION_PREFETCH_THRESHOLD = 6

@Composable
private fun LibraryTabContent(
    viewModel: GutenbergBrowseViewModel,
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
                placeholder = { Text("Search Project Gutenberg…") },
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
                        columns = GridCells.Fixed(3),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            CatalogItemCard(
                                item = item,
                                onClick = { viewModel.openDetail(item) },
                            )
                        }
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
private fun GutenbergHomeTab(
    onOpenDetail: (itemId: String) -> Unit,
    viewModel: GutenbergLibraryViewModel = hiltViewModel(),
) {
    val inProgress by viewModel.inProgress.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()

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
private fun GutenbergToReadTab(
    onOpenDetail: (itemId: String) -> Unit,
    viewModel: GutenbergLibraryViewModel = hiltViewModel(),
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
private fun GutenbergAnnotationsTab(
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
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
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
                    "📖",
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
