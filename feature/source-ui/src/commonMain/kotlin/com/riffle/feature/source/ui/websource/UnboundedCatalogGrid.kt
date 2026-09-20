package com.riffle.feature.source.ui.websource

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.library.CoverGridLayout
import com.riffle.feature.source.ui.EmptyLibrary
import com.riffle.feature.source.ui.LocalCoverGridScale
import com.riffle.feature.source.ui.OfflineBanner
import com.riffle.feature.source.ui.fadingScrollbar
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_no_results
import com.riffle.feature.source.ui.pinchCoverZoom
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

private const val PAGINATION_PREFETCH_THRESHOLD = 6

/**
 * Minimum cover-cell size for the unbounded browse grids, on both hosts.
 *
 * Reads the window width the way `:shared`'s `coverGridMinCell()` already does
 * ([LocalWindowInfo] rather than Android's `LocalConfiguration`) because this composable is
 * rendered by Kotlin/Native too. The breakpoint numbers and the pinch multiplier both come from
 * [CoverGridLayout], so an iPad and an Android tablet lay their covers out identically.
 */
@Composable
internal fun unboundedCoverGridMinCellSize(): Dp {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    return CoverGridLayout.minCellSizeDp(widthDp.value, LocalCoverGridScale.current).dp
}

/**
 * Shared content area for unbounded web source browse screens.
 * Renders: offline banner (when offline) + loading spinner / error / empty state / grid.
 */
@Composable
fun <T> UnboundedBrowseContent(
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
                            stringResource(Res.string.ui_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        EmptyLibrary(modifier = Modifier.align(Alignment.Center).padding(24.dp))
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
fun UnboundedCoverGridZoomProvider(
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
 * Shared zoomable, adaptive grid for Chitanka, Gutenberg, radio.es and future unbounded web
 * catalogues. [coverCellSizeMultiplier] keeps square audiobook art roomier at the default density
 * while using the same global pinch scale as portrait book covers.
 */
@Composable
fun <T> UnboundedCatalogGrid(
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
        columns = GridCells.Adaptive(unboundedCoverGridMinCellSize() * coverCellSizeMultiplier),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .pinchCoverZoom(LocalCoverGridScale.current, onCoverScaleChange)
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
