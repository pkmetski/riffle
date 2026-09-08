package com.riffle.app.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import com.riffle.app.ui.theme.RiffleAppIcon
import com.riffle.app.ui.theme.RiffleIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.annotations.AnnotationsListScreen
import com.riffle.app.feature.navigation.sourceDisplayName
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.BookshelfViewModel
import com.riffle.feature.library.LibrarySectionType
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenDrawer: () -> Unit,
    onItemSelected: (sourceId: String, itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit = onItemSelected,
) {
    val viewModel: BookshelfViewModel = koinViewModel()
    val inProgress by viewModel.inProgress.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val toRead by viewModel.toRead.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val sources by viewModel.sources.collectAsState()

    val sourceBadgeMap = sources.associate { it.id to sourceDisplayName(it) }
    val authTokenMap = viewModel.authTokenMap

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RiffleAppIcon(size = 24.dp, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            text = "BOOKSHELF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "In Progress") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = "To Read") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(RiffleIcons.Annotations, contentDescription = "Annotations") },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> InProgressTabContent(
                inProgress = inProgress,
                continueSeries = continueSeries,
                sourceBadgeMap = sourceBadgeMap,
                authTokenMap = authTokenMap,
                innerPadding = innerPadding,
                onItemSelected = { onItemSelected(it.sourceId, it.id) },
            )
            1 -> ToReadTabContent(
                items = toRead,
                sourceBadgeMap = sourceBadgeMap,
                authTokenMap = authTokenMap,
                innerPadding = innerPadding,
                onItemSelected = { onItemSelected(it.sourceId, it.id) },
            )
            else -> AnnotationsTabContent(
                annotations = annotations,
                authTokenMap = authTokenMap,
                innerPadding = innerPadding,
                onBookClick = onAnnotatedBookClick,
            )
        }
    }
}

@Composable
private fun InProgressTabContent(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    sourceBadgeMap: Map<String, String>,
    authTokenMap: Map<String, String>,
    innerPadding: PaddingValues,
    onItemSelected: (LibraryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item(key = "header_in_progress") { SectionHeader(LibrarySectionType.IN_PROGRESS.title) }
            item(key = "grid_in_progress") {
                BookSectionGrid(
                    items = inProgress,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
        if (continueSeries.isNotEmpty()) {
            item(key = "header_continue_series") { SectionHeader(LibrarySectionType.CONTINUE_SERIES.title) }
            item(key = "grid_continue_series") {
                BookSectionGrid(
                    items = continueSeries,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    showSeriesBadge = true,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
    }
}

@Composable
private fun ToReadTabContent(
    items: List<LibraryItem>,
    sourceBadgeMap: Map<String, String>,
    authTokenMap: Map<String, String>,
    innerPadding: PaddingValues,
    onItemSelected: (LibraryItem) -> Unit,
) {
    if (items.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
        items(items, key = { "${it.sourceId}_${it.id}" }) { item ->
            BookCoverTile(
                item = item,
                token = authTokenMap[item.sourceId] ?: "",
                onClick = { onItemSelected(item) },
                sourceBadge = sourceBadgeMap[item.sourceId],
            )
        }
    }
}

@Composable
private fun AnnotationsTabContent(
    annotations: List<AnnotatedBook>,
    authTokenMap: Map<String, String>,
    innerPadding: PaddingValues,
    onBookClick: (sourceId: String, itemId: String) -> Unit,
) {
    AnnotationsListScreen(
        state = AnnotationsListUiState(loading = false, books = annotations),
        onBookClick = onBookClick,
        tokenMap = authTokenMap,
        modifier = Modifier.padding(innerPadding),
    )
}
