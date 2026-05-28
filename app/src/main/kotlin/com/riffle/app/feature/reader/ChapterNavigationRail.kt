package com.riffle.app.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    onSegmentClick: (RailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    val barColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val dividerColor = MaterialTheme.colorScheme.outline
    val cursorColor = MaterialTheme.colorScheme.primary

    val activeTitle = segments.getOrNull(activeIndex)?.title ?: ""

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .testTag("chapter_navigation_rail")
            .semantics { contentDescription = "Active rail segment: $activeTitle" }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    val idx = (offset.x / size.width * segments.size)
                        .toInt()
                        .coerceIn(0, segments.size - 1)
                    onSegmentClick(segments[idx])
                }
            }
            .drawWithCache {
                val n = segments.size
                val segW = size.width / n
                onDrawBehind {
                    drawRect(color = barColor)
                    drawRect(
                        color = activeColor,
                        topLeft = Offset(activeIndex * segW, 0f),
                        size = Size(segW, size.height),
                    )
                    for (i in 1 until n) {
                        val x = segW * i
                        drawLine(
                            color = dividerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val cx = cursorPosition.coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = cursorColor,
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}
