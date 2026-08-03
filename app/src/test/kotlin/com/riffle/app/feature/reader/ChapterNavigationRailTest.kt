package com.riffle.app.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterNavigationRailTest {

    @Test
    fun `flat segments retain the requested four dp rail path`() {
        val segments = listOf(
            RailSegment("One", "one.xhtml"),
            RailSegment("Two", "two.xhtml"),
        )

        assertFalse(chapterRailUsesGroups(segments))
        assertEquals(4.dp, chapterRailHeight(4.dp))
        assertFalse(chapterRailUsesColorProgress(segments))
    }

    @Test
    fun `grouped segments retain the requested four dp single-strip rail path`() {
        val segments = listOf(
            RailSegment("One", "one.xhtml", groupIndex = 0),
            RailSegment("Two", "two.xhtml", groupIndex = 1),
        )

        assertTrue(chapterRailUsesGroups(segments))
        assertEquals(4.dp, chapterRailHeight(4.dp))
        assertTrue(chapterRailUsesColorProgress(segments))
        assertFalse(chapterRailUsesColorProgress(segments, coloredChapterMap = false))
        // 0.5f (bumped from the original 0.35f) keeps unread chapters clearly colored while the
        // fully-saturated read portion still reads as the progress fill.
        assertEquals(0.5f, CHAPTER_RAIL_UNREAD_ALPHA)
        assertEquals(
            Color(0xFFE66100).copy(alpha = CHAPTER_RAIL_UNREAD_ALPHA),
            chapterRailUnreadGroupColor(groupIndex = 0),
        )
        assertEquals(4.dp, CHAPTER_RAIL_CURSOR_HALO_WIDTH)
        assertEquals(2.dp, CHAPTER_RAIL_CURSOR_CORE_WIDTH)
    }

    @Test
    fun `group palette uses eight high contrast color blind friendly colors`() {
        assertEquals(
            listOf(
                Color(0xFFE66100),
                Color(0xFF0072B2),
                Color(0xFF009E73),
                Color(0xFFCC3311),
                Color(0xFFAA4499),
                Color(0xFF00A6D6),
                Color(0xFFEE3377),
                Color(0xFFB8860B),
            ),
            CHAPTER_RAIL_GROUP_COLORS,
        )
        assertEquals(0, chapterRailGroupColorIndex(0))
        assertEquals(7, chapterRailGroupColorIndex(7))
        assertEquals(0, chapterRailGroupColorIndex(8))
        assertEquals(3, chapterRailGroupColorIndex(11))
    }

    @Test
    fun `bookmark markers are haloed dots bulging out of the rail`() {
        // Pins the dot-marker geometry: the 2.5dp dot radius exceeds the 2dp rail half-height so
        // the dot bulges out of the 4dp strip (a flush mark would read as another chapter-boundary
        // gap), and the halo ring around it keeps the dot visible over a same-hue segment.
        assertEquals(2.5.dp, CHAPTER_RAIL_BOOKMARK_DOT_RADIUS)
        assertEquals(4.dp, CHAPTER_RAIL_BOOKMARK_HALO_RADIUS)
    }

    @Test
    fun `bookmark dots map rail fractions to x and clamp invalid bounds`() {
        assertEquals(
            listOf(0f, 25f, 100f),
            chapterRailBookmarkXs(listOf(-0.2f, 0.25f, 1.4f), totalWidth = 100f),
        )
    }

    @Test
    fun `progress percentage is rounded and clamped for accessibility`() {
        assertEquals(0, chapterRailProgressPercent(-0.2f))
        assertEquals(38, chapterRailProgressPercent(0.375f))
        assertEquals(100, chapterRailProgressPercent(1.4f))
    }
}
