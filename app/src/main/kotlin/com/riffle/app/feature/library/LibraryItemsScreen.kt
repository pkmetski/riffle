package com.riffle.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun LibraryItemsScreen(
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onSeriesSelected: (Series) -> Unit,
    onCollectionSelected: (Collection) -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    viewModel: LibraryItemsViewModel = hiltViewModel(),
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredSeries by viewModel.filteredSeries.collectAsState()
    val filteredCollections by viewModel.filteredCollections.collectAsState()
    val filteredUngroupedItems by viewModel.filteredUngroupedItems.collectAsState()

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
            val allEmpty = filteredSeries.isEmpty() && filteredCollections.isEmpty() && filteredUngroupedItems.isEmpty()
            if (allEmpty && !queryActive) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No items in this library")
                }
            } else if (allEmpty && queryActive) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results for '$searchQuery'")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (filteredSeries.isNotEmpty()) {
                        item {
                            SectionHeader("Series")
                        }
                        items(filteredSeries, key = { "series_${it.id}" }) { s ->
                            SeriesCard(series = s, token = viewModel.authToken, onClick = { onSeriesSelected(s) })
                        }
                    }
                    if (filteredCollections.isNotEmpty()) {
                        item {
                            SectionHeader("Collections")
                        }
                        items(filteredCollections, key = { "col_${it.id}" }) { col ->
                            CollectionCard(collection = col, onClick = { onCollectionSelected(col) })
                        }
                    }
                    if (filteredUngroupedItems.isNotEmpty()) {
                        item {
                            SectionHeader("Books")
                        }
                        items(filteredUngroupedItems, key = { "item_${it.id}" }) { item ->
                            LibraryItemCard(item = item, token = viewModel.authToken, onClick = { onItemSelected(item) })
                        }
                    }
                }
            }
        }
    }
}

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
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SeriesCard(series: Series, token: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                modifier = Modifier.size(width = 64.dp, height = 96.dp),
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
private fun CollectionCard(collection: Collection, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

@Composable
internal fun LibraryItemCard(item: LibraryItem, token: String, onClick: (() -> Unit)? = null) {
    val alpha = if (!item.isSupported) 0.38f else 1f
    val cardModifier = if (onClick != null)
        Modifier.fillMaxWidth().alpha(alpha).clickable(onClick = onClick)
    else
        Modifier.fillMaxWidth().alpha(alpha)
    Card(modifier = cardModifier) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 64.dp, height = 96.dp),
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
