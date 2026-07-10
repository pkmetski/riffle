package com.riffle.app.feature.source.chitanka

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.chitanka.ChitankaCatalog

/**
 * Chitanka Source browse screen. Dedicated route ("chitanka_browse/{rootId}/{name}")
 * distinct from LibraryItemsScreen — Chitanka has no Room-backed `library_items` mirror
 * so the screen consumes ChitankaCatalog directly through [ChitankaBrowseViewModel].
 *
 * Layout: TopAppBar (title = library name), inline search bar, horizontal chip strip
 * (server-side facet — ADR 0042), then a 3-column cover grid for Books / 2-column for
 * Audiobooks. Tapping a card opens a metadata detail sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChitankaBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    viewModel: ChitankaBrowseViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val facets by viewModel.facets.collectAsState()
    val selectedFacet by viewModel.selectedFacet.collectAsState()
    val query by viewModel.query.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var searchOpen by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<CatalogItem?>(null) }

    val isAudioRoot = viewModel.rootId == ChitankaCatalog.ROOT_AUDIOBOOKS

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
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                        )
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
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
                            LazyVerticalGrid(
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
                                        onClick = { detailItem = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detailItem?.let { item ->
        ChitankaDetailSheet(
            item = item,
            isAudio = isAudioRoot,
            onDismiss = { detailItem = null },
        )
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChitankaDetailSheet(
    item: CatalogItem,
    isAudio: Boolean,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!item.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .width(100.dp)
                            .aspectRatio(if (isAudio) 1f else (2f / 3f))
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    if (item.author.isNotBlank()) {
                        Text(item.author, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.seriesName?.let { name ->
                        val seq = item.seriesSequence?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        Text(
                            "📚 $name$seq",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (item.genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.genres.take(6).forEach { g ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                g,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (isAudio)
                    "Playback and download for Chitanka audiobooks will land in a follow-up (see PR)."
                else
                    "Reader and download integration for Chitanka books will land in a follow-up.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
