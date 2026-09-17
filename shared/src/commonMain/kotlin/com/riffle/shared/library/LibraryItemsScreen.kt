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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.core.models.Collection
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibraryProjection
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.LibraryTabVisibility
import com.riffle.shared.SharedUiIcons
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val SECTION_ROW_HEIGHT = 200
private const val SECTION_CELL_WIDTH = 120

/** Index of the Annotations tab — single source of truth shared by the bar and content switch. */
internal fun tabIndexForAnnotations(): Int = 2

/** Index of the Playlists tab — positioned after Collections (4) and before All Books (5). */
internal fun tabIndexForPlaylists(): Int = 6

/**
 * True when [selectedTab] is no longer visible and the UI should clamp back to Home.
 * Returns false while searching (filter changes tab visibility temporarily) or while
 * [visibility] is still null (resolving), so a rememberSaveable-restored tab survives the
 * initial load window.
 */
internal fun shouldClampSelectedTab(
    searchQuery: String,
    visibility: LibraryTabVisibility?,
    selectedTab: Int,
): Boolean {
    if (searchQuery.isNotEmpty()) return false
    if (visibility == null) return false
    return !isTabVisible(selectedTab, visibility)
}

/** True when the tab at [selectedTab] has data to show. Home (0) and All Books (5) are always visible. */
internal fun isTabVisible(selectedTab: Int, visibility: LibraryTabVisibility): Boolean =
    when (selectedTab) {
        1 -> visibility.toRead
        tabIndexForAnnotations() -> visibility.annotations
        3 -> visibility.series
        4 -> visibility.collections
        tabIndexForPlaylists() -> visibility.playlists
        else -> true
    }

@Composable
fun LibraryItemsScreen(
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (com.riffle.core.models.Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    viewModel: LibraryItemsViewModel = koinInject { parametersOf(libraryId) },
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
    val coversAreSquare by viewModel.coversAreSquare.collectAsState()
    val tabVisibility by viewModel.tabVisibility.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            } else {
                when (selectedTab) {
                    0 -> HomeTabContent(projection, coversAreSquare, onItemSelected, onSeriesSelected, onCollectionSelected, onSectionSeeMore)
                    1 -> SimpleItemList(projection.toRead, "To Read", onItemSelected)
                    tabIndexForAnnotations() -> AnnotationsTabContent(projection.annotations)
                    3 -> SeriesTabContent(projection.series, onSeriesSelected)
                    4 -> CollectionsTabContent(projection.collections, onCollectionSelected)
                    5 -> AllBooksTabContent(projection.allBooks, coversAreSquare, onItemSelected)
                    else -> HomeTabContent(projection, coversAreSquare, onItemSelected, onSeriesSelected, onCollectionSelected, onSectionSeeMore)
                }
            }
        }
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
    onItemSelected: (LibraryItem) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (projection.inProgress.isNotEmpty()) {
            item { SectionHeader("In Progress") { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) } }
            item { HorizontalBookRow(items = projection.inProgress.take(10), onItemClick = onItemSelected) }
        }
        if (projection.continueSeries.isNotEmpty()) {
            item { SectionHeader("Continue Series") { onSectionSeeMore(LibrarySectionType.CONTINUE_SERIES) } }
            item { HorizontalBookRow(items = projection.continueSeries.take(10), onItemClick = onItemSelected) }
        }
        if (projection.recentlyAdded.isNotEmpty()) {
            item { SectionHeader("Recently Added") { onSectionSeeMore(LibrarySectionType.RECENTLY_ADDED) } }
            item { HorizontalBookRow(items = projection.recentlyAdded.take(10), onItemClick = onItemSelected) }
        }
        if (projection.finished.isNotEmpty()) {
            item { SectionHeader("Finished") { onSectionSeeMore(LibrarySectionType.FINISHED) } }
            item { HorizontalBookRow(items = projection.finished.take(10), onItemClick = onItemSelected) }
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
            item { BookGrid(items = projection.allBooks, coversAreSquare = coversAreSquare, onItemClick = onItemSelected) }
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
                    DefaultCoverPlaceholder(isAudiobook = item.isListenable && !item.isReadable, modifier = Modifier.fillMaxSize())
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

@Composable
private fun AnnotationsTabContent(annotations: List<AnnotationSearchResult>) {
    if (annotations.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No annotations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(annotations, key = { it.annotation.id }) { result ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(result.bookTitle, style = MaterialTheme.typography.bodyLarge)
                if (result.annotation.textSnippet.isNotEmpty()) {
                    Text(
                        result.annotation.textSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
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
private fun AllBooksTabContent(items: List<LibraryItem>, coversAreSquare: Boolean, onItemSelected: (LibraryItem) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No books", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val aspect = if (coversAreSquare) 1f else 2f / 3f
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
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
            )
        }
    }
}

@Composable
private fun BookGrid(
    items: List<LibraryItem>,
    coversAreSquare: Boolean,
    onItemClick: (LibraryItem) -> Unit,
) {
    val aspect = if (coversAreSquare) 1f else 2f / 3f
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
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
            )
        }
    }
}

@Composable
fun BookCoverTile(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = item.title }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        DefaultCoverPlaceholder(
            isAudiobook = item.isListenable && !item.isReadable,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isDownloaded || item.isCached) {
            DownloadedBadge(
                downloaded = item.isDownloaded,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
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
