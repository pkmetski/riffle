package com.riffle.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
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
            val total = info.totalItemsCount
            if (total <= 0 || visible.isEmpty()) return@derivedStateOf null
            val viewport = (info.viewportEndOffset - info.viewportStartOffset)
                .toFloat().coerceAtLeast(1f)
            val avgItemSize = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
            val contentHeight = avgItemSize * total
            if (contentHeight <= viewport) return@derivedStateOf null
            val extent = (viewport / contentHeight).coerceIn(0f, 1f)
            val offsetPx =
                state.firstVisibleItemIndex * avgItemSize + state.firstVisibleItemScrollOffset
            val offset = (offsetPx / contentHeight).coerceIn(0f, 1f - extent)
            ScrollbarMetrics(offset, extent)
        }
    }

    thumbOverlay(alpha = { alpha.value }, metrics = { metrics }, color = resolvedColor)
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
            val total = info.totalItemsCount
            if (total <= 0 || visible.isEmpty()) return@derivedStateOf null
            val viewport = (info.viewportEndOffset - info.viewportStartOffset)
                .toFloat().coerceAtLeast(1f)
            val firstRow = visible.first().row
            val columns = visible.count { it.row == firstRow }.coerceAtLeast(1)
            val totalRows = (total + columns - 1) / columns
            val avgRowHeight = visible
                .groupBy { it.row }
                .map { (_, group) -> group.first().size.height.toFloat() }
                .average().toFloat().coerceAtLeast(1f)
            val contentHeight = avgRowHeight * totalRows
            if (contentHeight <= viewport) return@derivedStateOf null
            val extent = (viewport / contentHeight).coerceIn(0f, 1f)
            val firstItem = visible.first()
            val offsetPx = firstRow * avgRowHeight - firstItem.offset.y.toFloat()
            val offset = (offsetPx / contentHeight).coerceIn(0f, 1f - extent)
            ScrollbarMetrics(offset, extent)
        }
    }

    thumbOverlay(alpha = { alpha.value }, metrics = { metrics }, color = resolvedColor)
}

private data class ScrollbarMetrics(val offsetFraction: Float, val extentFraction: Float)

@Composable
private fun defaultThumbColor(): Color =
    androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

@Composable
private fun rememberFadingAlpha(isScrolling: () -> Boolean): Animatable<Float, *> {
    val alpha = remember { Animatable(0f) }
    val scrolling by remember { derivedStateOf(isScrolling) }
    androidx.compose.runtime.LaunchedEffect(scrolling) {
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
private fun Modifier.thumbOverlay(
    alpha: () -> Float,
    metrics: () -> ScrollbarMetrics?,
    color: Color,
): Modifier {
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { ThumbWidth.toPx() }
    val minLenPx = with(density) { ThumbMinLength.toPx() }
    val edgePx = with(density) { EdgeInset.toPx() }
    return drawWithContent {
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
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbWidthPx / 2f),
    )
}
