package com.riffle.app.feature.reader

import com.riffle.core.domain.comic.ComicBookmark
import org.junit.Assert.assertEquals
import org.junit.Test

class CbzRailSegmentsTest {

    @Test
    fun `no bookmarks produces single flat segment`() {
        val segments = buildCbzRailSegments(emptyList(), pageCount = 20)
        assertEquals(1, segments.size)
        assertEquals("", segments[0].title)
        assertEquals(null, segments[0].groupIndex)
        assertEquals(1f, segments[0].weight, 0.001f)
    }

    @Test
    fun `single bookmark produces one segment`() {
        val bookmarks = listOf(ComicBookmark(pageIndex = 5, title = "Story"))
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        assertEquals(1, segments.size)
        assertEquals("Story", segments[0].title)
        // weight = (20 - 5) / 20 = 0.75f
        assertEquals(0.75f, segments[0].weight, 0.001f)
        assertEquals(0, segments[0].groupIndex!!)
    }

    @Test
    fun `two bookmarks produces two segments with proportional weights`() {
        // 20 pages total; bookmarks at 0 and 10 → two segments of 10 pages each
        val bookmarks = listOf(
            ComicBookmark(pageIndex = 0, title = "Story 1"),
            ComicBookmark(pageIndex = 10, title = "Story 2"),
        )
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        assertEquals(2, segments.size)
        assertEquals("Story 1", segments[0].title)
        assertEquals("Story 2", segments[1].title)
        assertEquals(0.5f, segments[0].weight, 0.001f)
        assertEquals(0.5f, segments[1].weight, 0.001f)
        assertEquals(0, segments[0].groupIndex!!)
        assertEquals(1, segments[1].groupIndex!!)
    }

    @Test
    fun `bookmarks are sorted by pageIndex before building`() {
        val bookmarks = listOf(
            ComicBookmark(pageIndex = 10, title = "Story 2"),
            ComicBookmark(pageIndex = 0, title = "Story 1"),
        )
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        assertEquals("Story 1", segments[0].title)
        assertEquals("Story 2", segments[1].title)
    }

    @Test
    fun `last segment extends to end of book`() {
        val bookmarks = listOf(ComicBookmark(pageIndex = 0, title = "Only Story"))
        val segments = buildCbzRailSegments(bookmarks, pageCount = 10)
        assertEquals(1f, segments[0].weight, 0.001f)  // 10/10 pages = 1.0
    }

    @Test
    fun `page count 0 returns empty list`() {
        val segments = buildCbzRailSegments(emptyList(), pageCount = 0)
        assertEquals(0, segments.size)
    }

    // ── findActiveCbzSegmentIndex ─────────────────────────────────────────────

    @Test
    fun `single flat segment always returns 0`() {
        val segments = buildCbzRailSegments(emptyList(), pageCount = 20)
        assertEquals(0, findActiveCbzSegmentIndex(segments, currentPage = 0))
        assertEquals(0, findActiveCbzSegmentIndex(segments, currentPage = 19))
    }

    @Test
    fun `returns last segment whose start is at or before current page`() {
        val bookmarks = listOf(
            ComicBookmark(pageIndex = 0, title = "S1"),
            ComicBookmark(pageIndex = 10, title = "S2"),
        )
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        assertEquals(0, findActiveCbzSegmentIndex(segments, currentPage = 0))
        assertEquals(0, findActiveCbzSegmentIndex(segments, currentPage = 9))
        assertEquals(1, findActiveCbzSegmentIndex(segments, currentPage = 10))
        assertEquals(1, findActiveCbzSegmentIndex(segments, currentPage = 19))
    }

    @Test
    fun `returns -1 for empty segments`() {
        assertEquals(-1, findActiveCbzSegmentIndex(emptyList(), currentPage = 5))
    }

    // ── cbzRailCursorPosition ─────────────────────────────────────────────────

    @Test
    fun `flat mode cursor is page fraction`() {
        val segments = buildCbzRailSegments(emptyList(), pageCount = 10)
        assertEquals(0f, cbzRailCursorPosition(segments, activeIndex = 0, currentPage = 0, pageCount = 10), 0.001f)
        assertEquals(0.5f, cbzRailCursorPosition(segments, activeIndex = 0, currentPage = 5, pageCount = 10), 0.1f)
        assertEquals(1f, cbzRailCursorPosition(segments, activeIndex = 0, currentPage = 9, pageCount = 10), 0.001f)
    }

    @Test
    fun `segmented cursor at segment boundary`() {
        val bookmarks = listOf(
            ComicBookmark(pageIndex = 0, title = "S1"),
            ComicBookmark(pageIndex = 10, title = "S2"),
        )
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        // At page 10 (start of segment 1) cursor should be 0.5 (halfway across the equal-weight rail)
        assertEquals(0.5f, cbzRailCursorPosition(segments, activeIndex = 1, currentPage = 10, pageCount = 20), 0.001f)
    }

    @Test
    fun `cursor returns 0 for empty segments`() {
        assertEquals(0f, cbzRailCursorPosition(emptyList(), activeIndex = 0, currentPage = 5, pageCount = 10), 0.001f)
    }

    @Test
    fun `cursor reaches 1_0 on last page of multi-segment book`() {
        val bookmarks = listOf(
            ComicBookmark(pageIndex = 0, title = "S1"),
            ComicBookmark(pageIndex = 10, title = "S2"),
        )
        val segments = buildCbzRailSegments(bookmarks, pageCount = 20)
        assertEquals(
            1f,
            cbzRailCursorPosition(segments, activeIndex = 1, currentPage = 19, pageCount = 20),
            0.001f,
        )
    }
}
