package com.riffle.app.feature.source.websource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.library.LocalCoverGridScale
import com.riffle.app.feature.library.OfflineBanner
import com.riffle.app.feature.library.coverGridMinCellSize
import com.riffle.app.feature.library.pinchCoverZoom
import com.riffle.app.ui.fadingScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PAGINATION_PREFETCH_THRESHOLD = 6

/**
 * Shared content area for unbounded web source browse screens.
 * Renders: offline banner (when offline) + loading spinner / error / empty state / grid.
 */
@Composable
internal fun <T> UnboundedBrowseContent(
    isOffline: Boolean,
    isLoading: Boolean,
    error: String?,
    items: List<T>,
    query: String,
    isPaging: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    coverCellSizeMultiplier: Float = 1f,
    itemContent: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (isOffline) OfflineBanner()
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && items.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.wrapContentSize().align(Alignment.Center))
                error != null && items.isEmpty() ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                items.isEmpty() ->
                    if (query.isNotBlank()) {
                        Text(
                            stringResource(R.string.ui_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        com.riffle.app.ui.EmptyLibrary(modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    }
                else ->
                    UnboundedCatalogGrid(
                        items = items,
                        isPaging = isPaging,
                        hasMore = hasMore,
                        onLoadMore = onLoadMore,
                        onCoverScaleChange = onCoverScaleChange,
                        itemKey = itemKey,
                        coverCellSizeMultiplier = coverCellSizeMultiplier,
                        itemContent = itemContent,
                    )
            }
        }
    }
}

/**
 * Supplies the global, persisted cover density to every tab in an unbounded web source while
 * keeping gesture updates live. Persistence is deliberately delegated to the ViewModel so writes
 * can be debounced once the gesture settles.
 */
@Composable
internal fun UnboundedCoverGridZoomProvider(
    persistedScale: Float,
    onPersistScaleChange: (Float) -> Unit,
    content: @Composable (onScaleChange: (Float) -> Unit) -> Unit,
) {
    var liveScale by remember { mutableFloatStateOf(persistedScale) }
    LaunchedEffect(persistedScale) { liveScale = persistedScale }
    val onScaleChange: (Float) -> Unit = {
        liveScale = it
        onPersistScaleChange(it)
    }

    CompositionLocalProvider(LocalCoverGridScale provides liveScale) {
        content(onScaleChange)
    }
}

/**
 * Shared zoomable, adaptive grid for Chitanka, Gutenberg, and future unbounded web catalogues.
 * [coverCellSizeMultiplier] keeps square audiobook art roomier at the default density while using
 * the same global pinch scale as portrait book covers.
 */
@Composable
internal fun <T> UnboundedCatalogGrid(
    items: List<T>,
    isPaging: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onCoverScaleChange: (Float) -> Unit,
    itemKey: (T) -> Any,
    coverCellSizeMultiplier: Float = 1f,
    itemContent: @Composable (T) -> Unit,
) {
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
            if (should && hasMore) onLoadMore()
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(coverGridMinCellSize() * coverCellSizeMultiplier),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .pinchCoverZoom(onCoverScaleChange)
            .fillMaxSize()
            .fadingScrollbar(gridState),
    ) {
        items(items, key = itemKey) { item ->
            itemContent(item)
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
