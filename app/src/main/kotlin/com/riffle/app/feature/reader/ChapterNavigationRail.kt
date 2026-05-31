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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    // Foreground (onSurface) instead of `outline` so dividers read as crisp chapter
    // boundaries on top of the filled track, not as soft chrome.
    val dividerColor = MaterialTheme.colorScheme.onSurface
    val activeOutlineColor = MaterialTheme.colorScheme.primary
    val cursorColor = MaterialTheme.colorScheme.primary

    val activeTitle = segments.getOrNull(activeIndex)?.title ?: ""
    val clampedCursor = cursorPosition.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .testTag("chapter_navigation_rail")
            .semantics { contentDescription = "Active rail segment: $activeTitle" }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    val idx = railSegmentIndexAt(segments, offset.x, size.width.toFloat())
                    if (idx in segments.indices) onSegmentClick(segments[idx])
                }
            }
            .drawWithCache {
                val bounds = railSegmentBounds(segments, size.width)
                onDrawBehind {
                    // 1. Empty track.
                    drawRect(color = barColor)

                    // 2. Continuous progress underlay up to the cursor.
                    val fillWidth = clampedCursor * size.width
                    if (fillWidth > 0f) {
                        drawRect(
                            color = fillColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(fillWidth, size.height),
                        )
                    }

                    // 3. Chapter dividers (skip index 0 which is the start of the bar).
                    for (i in 1 until bounds.size) {
                        val x = bounds[i].first
                        drawLine(
                            color = dividerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    // 4. Active-chapter outline.
                    if (activeIndex in bounds.indices) {
                        val (ax, aw) = bounds[activeIndex]
                        val stroke = 1.5.dp.toPx()
                        drawRect(
                            color = activeOutlineColor,
                            topLeft = Offset(ax + stroke / 2f, stroke / 2f),
                            size = Size(
                                width = (aw - stroke).coerceAtLeast(0f),
                                height = (size.height - stroke).coerceAtLeast(0f),
                            ),
                            style = Stroke(width = stroke),
                        )
                    }

                    // 5. Cursor.
                    val cx = clampedCursor * size.width
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
