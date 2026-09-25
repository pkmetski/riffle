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
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.FilteredBooksViewModel
import com.riffle.feature.library.facetTitle
import com.riffle.feature.source.ui.OfflineBanner

/**
 * The books matching one metadata facet — an author, a genre, a year, a language, or "has a
 * readaloud". Rendered by both hosts.
 *
 * [tileContent] is the one genuinely host-specific part, for the same reason as
 * [PlaylistDetailScreen]'s `itemContent`: `:app` supplies its `BookCoverTile` (authenticated
 * cover art through its OkHttp Coil loader), `:shared` supplies its own. [minCellSize] likewise
 * comes from the host, because each derives the window width differently — Android from its
 * `WindowSizeClass`, iOS from `LocalWindowInfo` — while the breakpoint itself is
 * `CoverGridLayout`'s on both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredBooksScreen(
    viewModel: FilteredBooksViewModel,
    labels: FilteredBooksLabels,
    minCellSize: Dp,
    onItemSelected: (LibraryItem) -> Unit,
    onNavigateBack: () -> Unit,
    tileContent: @Composable (item: LibraryItem, token: String, onClick: () -> Unit) -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(facetTitle(viewModel.facetType, viewModel.facetValue), maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag(TestTags.NAV_BACK),
                    ) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = labels.back)
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
                    Text(labels.noBooksFound)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minCellSize),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    modifier = Modifier.fillMaxSize().testTag(TestTags.FILTERED_BOOKS_GRID),
                ) {
                    items(items, key = { it.id }) { item ->
                        Box(modifier = Modifier.padding(4.dp)) {
                            tileContent(item, viewModel.authToken) { onItemSelected(item) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The two strings [FilteredBooksScreen] draws that are not the facet's own value, supplied by the
 * host. Same arrangement and same reason as [PlaylistLabels].
 */
data class FilteredBooksLabels(
    /** Back-arrow content description. */
    val back: String,
    val noBooksFound: String,
) {
    companion object {
        /** Verbatim from `app/src/main/res/values/strings.xml`; used by the iOS host. */
        val English = FilteredBooksLabels(
            back = "Back",
            noBooksFound = "No books found",
        )
    }
}
