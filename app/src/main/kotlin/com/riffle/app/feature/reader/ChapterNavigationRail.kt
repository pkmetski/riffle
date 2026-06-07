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
import com.riffle.core.domain.ReaderTheme

@Composable
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    readerTheme: ReaderTheme,
    onSegmentClick: (RailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    // Colour the rail from the reader page palette, not MaterialTheme — the rail sits directly on
    // the page background (white / sepia / black), so MaterialTheme.surfaceVariant has no reliable
    // contrast there (it's near-white on a white page and the unread track vanishes). Page
    // foreground at graded alpha guarantees the track is visible on every theme.
    val pageForeground = readerTheme.palette.foreground
    val barColor = pageForeground.copy(alpha = 0.30f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val cursorColor = MaterialTheme.colorScheme.primary

    val activeTitle = segments.getOrNull(activeIndex)?.title ?: ""
    val clampedCursor = cursorPosition.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
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
                // Chapter boundaries are gaps punched into the bar — half a gap is shaved off each
                // interior edge of a segment so adjacent segments leave one full gap between them.
                // Outer edges (start of the first segment, end of the last) stay flush.
                val gap = 2.5.dp.toPx()
                val fillX = clampedCursor * size.width
                onDrawBehind {
                    bounds.forEachIndexed { i, (start, width) ->
                        val x0 = start + (if (i == 0) 0f else gap / 2f)
                        val x1 = start + width - (if (i == bounds.lastIndex) 0f else gap / 2f)
                        val w = (x1 - x0).coerceAtLeast(0f)
                        if (w <= 0f) return@forEachIndexed

                        // Track for this chapter.
                        drawRect(color = barColor, topLeft = Offset(x0, 0f), size = Size(w, size.height))

                        // Continuous progress fill, clamped to this segment's span.
                        if (fillX > x0) {
                            val fw = (minOf(x1, fillX) - x0).coerceAtLeast(0f)
                            if (fw > 0f) {
                                drawRect(color = fillColor, topLeft = Offset(x0, 0f), size = Size(fw, size.height))
                            }
                        }
                    }

                    // Cursor marking the exact reading position.
                    drawLine(
                        color = cursorColor,
                        start = Offset(fillX, 0f),
                        end = Offset(fillX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}
