package com.riffle.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.core.domain.Collection
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.Series

private const val SECTION_PREVIEW_LIMIT = 5

@Composable
fun LibraryItemsScreen(
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    viewModel: LibraryItemsViewModel = hiltViewModel(),
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredSeries by viewModel.filteredSeries.collectAsState()
    val filteredCollections by viewModel.filteredCollections.collectAsState()
    val filteredUngroupedItems by viewModel.filteredUngroupedItems.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val allBooks by viewModel.allBooks.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val series by viewModel.series.collectAsState()
    val collections by viewModel.collections.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboardController?.hide() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onSearchQueryChange("")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LibrarySearchHeader(
                libraryName = libraryName,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onOpenDrawer = onOpenDrawer,
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (viewModel.isOffline) {
                OfflineBanner()
            }

            val queryActive = searchQuery.isNotEmpty()
            if (queryActive) {
                SearchResultsContent(
                    query = searchQuery,
                    filteredSeries = filteredSeries,
                    filteredCollections = filteredCollections,
                    filteredItems = filteredUngroupedItems,
                    token = viewModel.authToken,
                    onSeriesSelected = onSeriesSelected,
                    onCollectionSelected = onCollectionSelected,
                    onItemSelected = onItemSelected,
                )
            } else {
                val allSectionsEmpty = inProgress.isEmpty() && series.isEmpty() &&
                        collections.isEmpty() && allBooks.isEmpty() && finished.isEmpty()
                if (allSectionsEmpty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items in this library")
                    }
                } else {
                    SectionedLibraryContent(
                        inProgress = inProgress,
                        series = series,
                        collections = collections,
                        allBooks = allBooks,
                        finished = finished,
                        token = viewModel.authToken,
                        onItemSelected = onItemSelected,
                        onSeriesSelected = onSeriesSelected,
                        onCollectionSelected = onCollectionSelected,
                        onSectionSeeMore = onSectionSeeMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionedLibraryContent(
    inProgress: List<LibraryItem>,
    series: List<Series>,
    collections: List<Collection>,
    allBooks: List<LibraryItem>,
    finished: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item(key = "header_in_progress") { SectionHeader(LibrarySectionType.IN_PROGRESS.displayName) }
            item(key = "grid_in_progress") {
                BookSectionGrid(
                    items = inProgress,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = if (inProgress.size > SECTION_PREVIEW_LIMIT) {
                        { onSectionSeeMore(LibrarySectionType.IN_PROGRESS) }
                    } else null,
                )
            }
        }
        if (series.isNotEmpty()) {
            item(key = "header_series") { SectionHeader(LibrarySectionType.SERIES.displayName) }
            item(key = "grid_series") {
                SeriesSectionGrid(
                    items = series,
                    token = token,
                    onSeriesSelected = onSeriesSelected,
                    onSeeMore = if (series.size > SECTION_PREVIEW_LIMIT) {
                        { onSectionSeeMore(LibrarySectionType.SERIES) }
                    } else null,
                )
            }
        }
        if (collections.isNotEmpty()) {
            item(key = "header_collections") { SectionHeader(LibrarySectionType.COLLECTIONS.displayName) }
            item(key = "grid_collections") {
                CollectionsSectionGrid(
                    items = collections,
                    onCollectionSelected = onCollectionSelected,
                    onSeeMore = if (collections.size > SECTION_PREVIEW_LIMIT) {
                        { onSectionSeeMore(LibrarySectionType.COLLECTIONS) }
                    } else null,
                )
            }
        }
        if (allBooks.isNotEmpty()) {
            item(key = "header_all_books") { SectionHeader(LibrarySectionType.ALL_BOOKS.displayName) }
            item(key = "grid_all_books") {
                BookSectionGrid(
                    items = allBooks,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = if (allBooks.size > SECTION_PREVIEW_LIMIT) {
                        { onSectionSeeMore(LibrarySectionType.ALL_BOOKS) }
                    } else null,
                )
            }
        }
        if (finished.isNotEmpty()) {
            item(key = "header_finished") { SectionHeader(LibrarySectionType.FINISHED.displayName) }
            item(key = "grid_finished") {
                BookSectionGrid(
                    items = finished,
                    token = token,
                    onItemSelected = onItemSelected,
                    onSeeMore = if (finished.size > SECTION_PREVIEW_LIMIT) {
                        { onSectionSeeMore(LibrarySectionType.FINISHED) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    query: String,
    filteredSeries: List<Series>,
    filteredCollections: List<Collection>,
    filteredItems: List<LibraryItem>,
    token: String,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
) {
    val allEmpty = filteredSeries.isEmpty() && filteredCollections.isEmpty() && filteredItems.isEmpty()
    if (allEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results for '$query'")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (filteredSeries.isNotEmpty()) {
            item { SectionHeader("Series") }
            items(filteredSeries, key = { "series_${it.id}" }) { s ->
                SearchSeriesRow(series = s, token = token, onClick = { onSeriesSelected(s) })
            }
        }
        if (filteredCollections.isNotEmpty()) {
            item { SectionHeader("Collections") }
            items(filteredCollections, key = { "col_${it.id}" }) { col ->
                SearchCollectionRow(collection = col, onClick = { onCollectionSelected(col) })
            }
        }
        if (filteredItems.isNotEmpty()) {
            item { SectionHeader("Books") }
            items(filteredItems, key = { "item_${it.id}" }) { item ->
                LibraryItemCard(item = item, token = token, onClick = { onItemSelected(item) })
            }
        }
    }
}

// --- Section grids ---

@Composable
fun BookSectionGrid(
    items: List<LibraryItem>,
    token: String,
    onItemSelected: (LibraryItem) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    val preview = if (onSeeMore != null) items.take(SECTION_PREVIEW_LIMIT) else items
    val overflowCount = items.size - SECTION_PREVIEW_LIMIT
    CoverGrid(
        count = preview.size + (if (onSeeMore != null) 1 else 0),
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { index ->
        if (onSeeMore != null && index == preview.size) {
            SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
        } else {
            val item = preview[index]
            BookCoverTile(item = item, token = token, onClick = { onItemSelected(item) })
        }
    }
}

@Composable
fun SeriesSectionGrid(
    items: List<Series>,
    token: String,
    onSeriesSelected: (Series) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    val preview = if (onSeeMore != null) items.take(SECTION_PREVIEW_LIMIT) else items
    val overflowCount = items.size - SECTION_PREVIEW_LIMIT
    CoverGrid(
        count = preview.size + (if (onSeeMore != null) 1 else 0),
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { index ->
        if (onSeeMore != null && index == preview.size) {
            SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
        } else {
            val s = preview[index]
            SeriesCoverTile(series = s, token = token, onClick = { onSeriesSelected(s) })
        }
    }
}

@Composable
fun CollectionsSectionGrid(
    items: List<Collection>,
    onCollectionSelected: (Collection) -> Unit,
    onSeeMore: (() -> Unit)? = null,
) {
    val preview = if (onSeeMore != null) items.take(SECTION_PREVIEW_LIMIT) else items
    val overflowCount = items.size - SECTION_PREVIEW_LIMIT
    CoverGrid(
        count = preview.size + (if (onSeeMore != null) 1 else 0),
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { index ->
        if (onSeeMore != null && index == preview.size) {
            SeeMoreTile(overflowCount = overflowCount, onClick = onSeeMore)
        } else {
            val col = preview[index]
            CollectionCoverTile(collection = col, onClick = { onCollectionSelected(col) })
        }
    }
}

// --- Generic 3-column grid layout ---

@Composable
private fun CoverGrid(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    val rows = (count + 2) / 3
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    Box(modifier = Modifier.weight(1f)) {
                        if (index < count) content(index)
                    }
                }
            }
        }
    }
}

// --- Cover tiles ---

@Composable
fun BookCoverTile(
    item: LibraryItem,
    token: String,
    onClick: () -> Unit,
) {
    val alpha = if (!item.isSupported) 0.38f else 1f
    Column(
        modifier = Modifier
            .alpha(alpha)
            .clickable(enabled = item.isSupported, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.readingProgress > 0f) {
                LinearProgressIndicator(
                    progress = { item.readingProgress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (item.isCached) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("C", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (item.isDownloaded) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("D", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SeriesCoverTile(
    series: Series,
    token: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(series.coverUrl)
                .addHeader("Authorization", "Bearer $token")
                .crossfade(true)
                .build(),
            contentDescription = series.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = series.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CollectionCoverTile(
    collection: Collection,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SeeMoreTile(overflowCount: Int, onClick: () -> Unit) {
    val dashColor = MaterialTheme.colorScheme.outline
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()), 0f)
                    drawRoundRect(
                        color = dashColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+$overflowCount\nmore",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// --- Search result rows (kept for search mode) ---

@Composable
private fun SearchSeriesRow(series: Series, token: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(series.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                contentDescription = series.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = series.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${series.bookCount} book${if (series.bookCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchCollectionRow(collection: Collection, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = collection.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = "${collection.bookCount} book${if (collection.bookCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Header / banner composables ---

@Composable
private fun LibrarySearchHeader(
    libraryName: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 16.dp),
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            }
            Text(
                text = libraryName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            val underlineColor = MaterialTheme.colorScheme.primary
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = underlineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    },
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun LibraryItemCard(item: LibraryItem, token: String, onClick: (() -> Unit)? = null) {
    val alpha = if (!item.isSupported) 0.38f else 1f
    Surface(
        modifier = if (onClick != null)
            Modifier.fillMaxWidth().alpha(alpha).clickable(onClick = onClick)
        else
            Modifier.fillMaxWidth().alpha(alpha),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!item.isSupported) {
                            Text(
                                text = "Not supported",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (item.isCached) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text("Cached", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (item.isDownloaded) {
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("Downloaded", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.readingProgress > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.readingProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(item.readingProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

