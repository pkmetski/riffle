package com.riffle.feature.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.BookCoverTile
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.feature.library.CoverGridLayout
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.feature.designsystem.generated.resources.Res as DsRes
import com.riffle.feature.designsystem.generated.resources.ui_no_books_in_this_series
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.OfflineBanner
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    libraryId: String,
    seriesName: String,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SeriesDetailViewModel = koinInject { parametersOf(seriesId, libraryId) },
) {
    val items by viewModel.items.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(seriesName) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag(TestTags.SERIES_DETAIL_BACK),
                    ) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding())) {
            if (isOffline) {
                OfflineBanner()
            }
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(DsRes.string.ui_no_books_in_this_series))
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
        columns = GridCells.Adaptive(coverGridMinCell()),
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
                    seriesNameBadge = CoverGridLayout.seriesPositionBadge(item.seriesName),
                )
            }
        }
    }
}
