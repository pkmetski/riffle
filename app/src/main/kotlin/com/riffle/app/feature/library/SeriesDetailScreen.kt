package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.core.models.LibraryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesName: String,
    onItemSelected: (com.riffle.core.models.LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(seriesName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding())) {
            if (isOffline) {
                OfflineBanner()
            }
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_books_in_this_series))
                }
            } else {
                SeriesDetailGrid(
                    items = items,
                    token = viewModel.authToken,
                    bottomPadding = padding.calculateBottomPadding(),
                    onItemSelected = onItemSelected,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun SeriesDetailGrid(
    items: List<LibraryItem>,
    token: String,
    bottomPadding: Dp = 0.dp,
    onItemSelected: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(coverGridMinCellSize()),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = bottomPadding + 16.dp,
        ),
        modifier = modifier,
    ) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.padding(4.dp)) {
                BookCoverTile(
                    item = item,
                    token = token,
                    onClick = { onItemSelected(item) },
                    seriesNameBadge = seriesPositionBadge(item.seriesName),
                )
            }
        }
    }
}

/**
 * [LibraryItem.seriesName] includes its sequence as a `#` suffix when the source provides one.
 * A series detail screen already names the series in its app bar, so only the position belongs on
 * each cover.
 */
internal fun seriesPositionBadge(seriesName: String?): String? {
    val sequence = seriesName
        ?.substringAfterLast(" #", missingDelimiterValue = "")
        ?.trim()
        .orEmpty()
    return sequence.takeIf { it.isNotEmpty() }?.let { "#$it" }
}
