package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.riffle.feature.source.ui.OfflineBanner
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
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.LibrarySectionType
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleScreen(
    onOpenDrawer: () -> Unit,
    onItemSelected: (sourceId: String, itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit = onItemSelected,
) {
    val viewModel: RiffleViewModel = koinViewModel()
    val inProgress by viewModel.inProgress.collectAsState()
    val continueSeries by viewModel.continueSeries.collectAsState()
    val toRead by viewModel.toRead.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

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
                            text = "RIFFLE",
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
                    icon = { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.ui_section_in_progress)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = stringResource(R.string.ui_to_read)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(RiffleIcons.Annotations, contentDescription = stringResource(R.string.ui_annotations)) },
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(innerPadding)) {
            if (isOffline) {
                OfflineBanner()
            }
            when (selectedTab) {
                0 -> InProgressTabContent(
                    inProgress = inProgress,
                    continueSeries = continueSeries,
                    sourceBadgeMap = sourceBadgeMap,
                    authTokenMap = authTokenMap,
                    onItemSelected = { onItemSelected(it.sourceId, it.id) },
                )
                1 -> ToReadTabContent(
                    items = toRead,
                    sourceBadgeMap = sourceBadgeMap,
                    authTokenMap = authTokenMap,
                    onItemSelected = { onItemSelected(it.sourceId, it.id) },
                )
                else -> AnnotationsTabContent(
                    annotations = annotations,
                    authTokenMap = authTokenMap,
                    onBookClick = onAnnotatedBookClick,
                )
            }
        }
    }
}

@Composable
private fun InProgressTabContent(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    sourceBadgeMap: Map<String, String>,
    authTokenMap: Map<String, String>,
    onItemSelected: (LibraryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (inProgress.isNotEmpty()) {
            item(key = "header_in_progress") { SectionHeader("${stringResource(R.string.ui_section_in_progress)} (${inProgress.size})") }
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
            item(key = "header_continue_series") { SectionHeader("${stringResource(R.string.ui_section_continue_series)} (${continueSeries.size})") }
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
    onItemSelected: (LibraryItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (items.isNotEmpty()) {
            item(key = "header_to_read") { SectionHeader(stringResource(R.string.ui_to_read_count, items.size)) }
            item(key = "grid_to_read") {
                BookSectionGrid(
                    items = items,
                    token = "",
                    tokenMap = authTokenMap,
                    onItemSelected = onItemSelected,
                    onSeeMore = null,
                    sourceBadgeProvider = { sourceBadgeMap[it.sourceId] },
                )
            }
        }
    }
}

@Composable
private fun AnnotationsTabContent(
    annotations: List<AnnotatedBook>,
    authTokenMap: Map<String, String>,
    onBookClick: (sourceId: String, itemId: String) -> Unit,
) {
    AnnotationsListScreen(
        state = AnnotationsListUiState(loading = false, books = annotations),
        onBookClick = onBookClick,
        tokenMap = authTokenMap,
    )
}
