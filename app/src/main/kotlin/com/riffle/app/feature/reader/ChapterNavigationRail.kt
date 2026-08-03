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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ReaderTheme
import kotlin.math.roundToInt

internal val CHAPTER_RAIL_GROUP_COLORS = listOf(
    Color(0xFFE66100), // Vivid orange
    Color(0xFF0072B2), // Strong blue
    Color(0xFF009E73), // Bluish green
    Color(0xFFCC3311), // Vermilion
    Color(0xFFAA4499), // Purple
    Color(0xFF00A6D6), // Cyan
    Color(0xFFEE3377), // Magenta
    Color(0xFFB8860B), // Dark amber
)

internal fun chapterRailUsesGroups(segments: List<RailSegment>): Boolean =
    segments.any { it.groupIndex != null }

internal fun chapterRailHeight(flatHeight: Dp): Dp =
    flatHeight

internal const val CHAPTER_RAIL_UNREAD_ALPHA = 0.5f
internal val CHAPTER_RAIL_CURSOR_HALO_WIDTH = 4.dp
internal val CHAPTER_RAIL_CURSOR_CORE_WIDTH = 2.dp
internal val CHAPTER_RAIL_BOOKMARK_DOT_RADIUS = 2.5.dp

// The halo is a ring around the dot, so it's defined relative to the dot radius rather than as an
// independent size. Its 2dp bleed past the 4dp rail lands in the overlay backdrop, which is painted
// the same reader-page background color the halo uses, so the overflow is invisible there.
internal val CHAPTER_RAIL_BOOKMARK_HALO_RADIUS = CHAPTER_RAIL_BOOKMARK_DOT_RADIUS + 1.5.dp

internal fun chapterRailUsesColorProgress(
    segments: List<RailSegment>,
    coloredChapterMap: Boolean = true,
): Boolean = coloredChapterMap && chapterRailUsesGroups(segments)

internal fun chapterRailGroupColorIndex(groupIndex: Int): Int =
    groupIndex % CHAPTER_RAIL_GROUP_COLORS.size

internal fun chapterRailUnreadGroupColor(groupIndex: Int): Color =
    CHAPTER_RAIL_GROUP_COLORS[chapterRailGroupColorIndex(groupIndex)]
        .copy(alpha = CHAPTER_RAIL_UNREAD_ALPHA)

internal fun chapterRailBookmarkXs(bookmarkPositions: List<Float>, totalWidth: Float): List<Float> =
    bookmarkPositions.map { it.coerceIn(0f, 1f) * totalWidth }

internal fun chapterRailProgressPercent(cursorPosition: Float): Int =
    (cursorPosition.coerceIn(0f, 1f) * 100).roundToInt()

@Composable
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    readerTheme: ReaderTheme,
    onSegmentClick: (RailSegment) -> Unit,
    coloredChapterMap: Boolean = true,
    bookmarkPositions: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    railHeight: Dp = 4.dp,
) {
    if (segments.isEmpty()) return

    // Colour the rail from the reader page palette, not MaterialTheme — the rail sits directly on
    // the page background (white / sepia / black), so MaterialTheme.surfaceVariant has no reliable
    // contrast there (it's near-white on a white page and the unread track vanishes). Page
    // foreground at graded alpha guarantees the track is visible on every theme.
    val pageForeground = readerTheme.palette.foreground
    val barColor = pageForeground.copy(alpha = 0.30f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    // Shared by the cursor and the bookmark dots: a page-background halo keeps either mark
    // visible over every chapter color and on all three reader themes.
    val haloColor = readerTheme.palette.background
    val cursorColor = pageForeground
    val bookmarkColor = MaterialTheme.colorScheme.primary

    val activeTitle = segments.getOrNull(activeIndex)?.title ?: ""
    val clampedCursor = cursorPosition.coerceIn(0f, 1f)
    val useColorProgress = chapterRailUsesColorProgress(segments, coloredChapterMap)
    val effectiveRailHeight = chapterRailHeight(railHeight)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(effectiveRailHeight)
            .testTag("chapter_navigation_rail")
            .semantics {
                contentDescription =
                    "Active rail segment: $activeTitle. Progress ${chapterRailProgressPercent(clampedCursor)}%"
            }
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
                val bookmarkDotRadius = CHAPTER_RAIL_BOOKMARK_DOT_RADIUS.toPx()
                val bookmarkHaloRadius = CHAPTER_RAIL_BOOKMARK_HALO_RADIUS.toPx()
                val bookmarkCenters = chapterRailBookmarkXs(bookmarkPositions, size.width)
                    .map { Offset(it, size.height / 2f) }
                onDrawBehind {
                    bounds.forEachIndexed { i, (start, width) ->
                        val x0 = start + (if (i == 0) 0f else gap / 2f)
                        val x1 = start + width - (if (i == bounds.lastIndex) 0f else gap / 2f)
                        val w = (x1 - x0).coerceAtLeast(0f)
                        if (w <= 0f) return@forEachIndexed

                        if (useColorProgress) {
                            val groupIndex = segments[i].groupIndex ?: 0
                            val groupColor = CHAPTER_RAIL_GROUP_COLORS[
                                chapterRailGroupColorIndex(groupIndex)
                            ]
                            // Keep unread sections identifiable by chapter color, but mute them
                            // enough that the saturated read portion forms an unmistakable progress
                            // bar without increasing the rail's approved 4dp height.
                            drawRect(
                                color = chapterRailUnreadGroupColor(groupIndex),
                                topLeft = Offset(x0, 0f),
                                size = Size(w, size.height),
                            )
                            if (fillX > x0) {
                                val readWidth = (minOf(x1, fillX) - x0).coerceAtLeast(0f)
                                if (readWidth > 0f) {
                                    drawRect(
                                        color = groupColor,
                                        topLeft = Offset(x0, 0f),
                                        size = Size(readWidth, size.height),
                                    )
                                }
                            }
                        } else {
                            // Flat books retain the original track and continuous progress fill.
                            drawRect(
                                color = barColor,
                                topLeft = Offset(x0, 0f),
                                size = Size(w, size.height),
                            )
                            if (fillX > x0) {
                                val fw = (minOf(x1, fillX) - x0).coerceAtLeast(0f)
                                if (fw > 0f) {
                                    drawRect(
                                        color = fillColor,
                                        topLeft = Offset(x0, 0f),
                                        size = Size(fw, size.height),
                                    )
                                }
                            }
                        }
                    }

                    // Bookmarks are round dots, not vertical ticks — a thin line reads as just
                    // another chapter-boundary gap on the 4dp rail.
                    bookmarkCenters.forEach { center ->
                        drawCircle(
                            color = haloColor,
                            radius = bookmarkHaloRadius,
                            center = center,
                        )
                        drawCircle(
                            color = bookmarkColor,
                            radius = bookmarkDotRadius,
                            center = center,
                        )
                    }

                    drawLine(
                        color = haloColor,
                        start = Offset(fillX, 0f),
                        end = Offset(fillX, size.height),
                        strokeWidth = CHAPTER_RAIL_CURSOR_HALO_WIDTH.toPx(),
                    )
                    drawLine(
                        color = cursorColor,
                        start = Offset(fillX, 0f),
                        end = Offset(fillX, size.height),
                        strokeWidth = CHAPTER_RAIL_CURSOR_CORE_WIDTH.toPx(),
                    )
                }
            },
    )
}
