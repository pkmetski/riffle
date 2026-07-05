package com.riffle.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val ThumbWidth: Dp = 3.dp
private val ThumbMinLength: Dp = 24.dp
private val EdgeInset: Dp = 2.dp
private const val FadeInMs = 120
private const val FadeOutMs = 400
private const val LingerMs = 700L

internal data class ScrollbarMetrics(val offsetFraction: Float, val extentFraction: Float)

fun Modifier.fadingScrollbar(
    state: LazyListState,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val resolvedColor = if (color == Color.Unspecified) defaultThumbColor() else color
    val alpha = rememberFadingAlpha { state.isScrollInProgress }

    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf null
            var sizeSum = 0L
            for (i in visible.indices) sizeSum += visible[i].size
            computeListScrollMetrics(
                total = info.totalItemsCount,
                viewport = info.viewportEndOffset - info.viewportStartOffset,
                visibleCount = visible.size,
                visibleSizeSum = sizeSum,
                firstVisibleIndex = state.firstVisibleItemIndex,
                firstVisibleScrollOffset = state.firstVisibleItemScrollOffset,
                firstVisibleItemSize = visible.first().size,
            )
        }
    }

    drawScrollbarOverlay(alpha = { alpha.value }, metrics = { metrics }, color = resolvedColor)
}

fun Modifier.fadingScrollbar(
    state: LazyGridState,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val resolvedColor = if (color == Color.Unspecified) defaultThumbColor() else color
    val alpha = rememberFadingAlpha { state.isScrollInProgress }

    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf null
            // Single pass: max items-in-a-row (spanning rows don't shrink columns),
            // and sum of the height of one item per visible row (first-of-row).
            var maxCols = 0
            var currentRow = Int.MIN_VALUE
            var countInRow = 0
            var rowHeightSum = 0L
            var rowCount = 0
            for (i in visible.indices) {
                val item = visible[i]
                if (item.row != currentRow) {
                    if (countInRow > maxCols) maxCols = countInRow
                    rowHeightSum += item.size.height
                    rowCount++
                    currentRow = item.row
                    countInRow = 1
                } else {
                    countInRow++
                }
            }
            if (countInRow > maxCols) maxCols = countInRow
            val first = visible.first()
            computeGridScrollMetrics(
                total = info.totalItemsCount,
                viewport = info.viewportEndOffset - info.viewportStartOffset,
                maxItemsInAnyVisibleRow = maxCols,
                rowHeightSum = rowHeightSum,
                visibleRowCount = rowCount,
                firstVisibleRow = first.row,
                firstItemOffsetY = first.offset.y,
            )
        }
    }

    drawScrollbarOverlay(alpha = { alpha.value }, metrics = { metrics }, color = resolvedColor)
}

internal fun computeListScrollMetrics(
    total: Int,
    viewport: Int,
    visibleCount: Int,
    visibleSizeSum: Long,
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    firstVisibleItemSize: Int,
): ScrollbarMetrics? {
    if (total <= 0 || viewport <= 0 || visibleCount <= 0) return null
    val avgItemSize = (visibleSizeSum.toFloat() / visibleCount).coerceAtLeast(1f)
    val contentHeight = avgItemSize * total
    if (contentHeight <= viewport) return null
    val extent = (viewport / contentHeight).coerceIn(0f, 1f)
    // Derive the offset in "items", not pixels, so a shifting avg item size (variable-height
    // rows like the annotations list) can't rescale the fraction mid-scroll. Within an item
    // we advance by scrollOffset/itemSize; at the item boundary scrollOffset resets to 0 and
    // firstVisibleIndex increments, so handoff is continuous.
    val itemSize = firstVisibleItemSize.coerceAtLeast(1)
    val perItemOffset = (firstVisibleIndex + firstVisibleScrollOffset.toFloat() / itemSize) / total
    val offset = perItemOffset.coerceIn(0f, 1f - extent)
    return ScrollbarMetrics(offset, extent)
}

internal fun computeGridScrollMetrics(
    total: Int,
    viewport: Int,
    maxItemsInAnyVisibleRow: Int,
    rowHeightSum: Long,
    visibleRowCount: Int,
    firstVisibleRow: Int,
    firstItemOffsetY: Int,
): ScrollbarMetrics? {
    if (total <= 0 || viewport <= 0 || visibleRowCount <= 0) return null
    val columns = maxItemsInAnyVisibleRow.coerceAtLeast(1)
    val totalRows = (total + columns - 1) / columns
    val avgRowHeight = (rowHeightSum.toFloat() / visibleRowCount).coerceAtLeast(1f)
    val contentHeight = avgRowHeight * totalRows
    if (contentHeight <= viewport) return null
    val extent = (viewport / contentHeight).coerceIn(0f, 1f)
    val offsetPx = firstVisibleRow * avgRowHeight - firstItemOffsetY
    val offset = (offsetPx / contentHeight).coerceIn(0f, 1f - extent)
    return ScrollbarMetrics(offset, extent)
}

@Composable
private fun defaultThumbColor(): Color =
    androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

@Composable
private fun rememberFadingAlpha(isScrolling: () -> Boolean): Animatable<Float, *> {
    val alpha = remember { Animatable(0f) }
    val scrolling by remember { derivedStateOf(isScrolling) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            alpha.animateTo(1f, tween(FadeInMs))
        } else {
            delay(LingerMs)
            alpha.animateTo(0f, tween(FadeOutMs))
        }
    }
    return alpha
}

@Composable
private fun Modifier.drawScrollbarOverlay(
    alpha: () -> Float,
    metrics: () -> ScrollbarMetrics?,
    color: Color,
): Modifier {
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { ThumbWidth.toPx() }
    val minLenPx = with(density) { ThumbMinLength.toPx() }
    val edgePx = with(density) { EdgeInset.toPx() }
    return this.drawWithContent {
        drawContent()
        val a = alpha()
        if (a <= 0f) return@drawWithContent
        val m = metrics() ?: return@drawWithContent
        drawThumb(m, a, color, thumbWidthPx, minLenPx, edgePx)
    }
}

private fun DrawScope.drawThumb(
    m: ScrollbarMetrics,
    alpha: Float,
    color: Color,
    thumbWidthPx: Float,
    minLenPx: Float,
    edgePx: Float,
) {
    val trackHeight = size.height
    val rawHeight = trackHeight * m.extentFraction
    val thumbHeight = rawHeight.coerceAtLeast(minLenPx).coerceAtMost(trackHeight)
    val maxTop = trackHeight - thumbHeight
    val thumbTop = (trackHeight * m.offsetFraction).coerceIn(0f, maxTop)
    val left = size.width - thumbWidthPx - edgePx
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(left, thumbTop),
        size = Size(thumbWidthPx, thumbHeight),
        cornerRadius = CornerRadius(thumbWidthPx / 2f),
    )
}
