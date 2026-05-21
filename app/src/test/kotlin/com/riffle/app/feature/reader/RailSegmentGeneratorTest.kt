package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RailSegmentGeneratorTest {

    private val chapter1 = TocEntry("Chapter 1: The Beginning", "chapter1.xhtml",
        listOf(TocEntry("1.1", "chapter1.xhtml#s1"), TocEntry("1.2", "chapter1.xhtml#s2")))
    private val chapter2 = TocEntry("Chapter 2: The Middle", "chapter2.xhtml",
        listOf(TocEntry("2.1", "chapter2.xhtml#s1"), TocEntry("2.2", "chapter2.xhtml#s2"), TocEntry("2.3", "chapter2.xhtml#s3")))
    private val chapter3 = TocEntry("Chapter 3: The End", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments ──────────────────────────────────────────────────

    @Test
    fun `returns one segment per top-level chapter`() {
        val segments = buildRailSegments(toc)
        assertEquals(3, segments.size)
        assertEquals(RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"), segments[0])
        assertEquals(RailSegment("Chapter 2: The Middle",    "chapter2.xhtml"), segments[1])
        assertEquals(RailSegment("Chapter 3: The End",       "chapter3.xhtml"), segments[2])
    }

    @Test
    fun `returns empty list for empty TOC`() {
        assertEquals(emptyList<RailSegment>(), buildRailSegments(emptyList()))
    }

    @Test
    fun `ignores subchapters — segments are always top-level`() {
        val segments = buildRailSegments(toc)
        // chapter1 has 2 children, chapter2 has 3, but segments are only the 3 top-level entries
        assertEquals(3, segments.size)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    private val bookSegments = listOf(
        RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"),
        RailSegment("Chapter 2: The Middle",    "chapter2.xhtml"),
        RailSegment("Chapter 3: The End",       "chapter3.xhtml"),
    )

    @Test
    fun `exact href match selects correct chapter`() {
        assertEquals(2, findActiveSegmentIndex(bookSegments, "chapter3.xhtml"))
    }

    @Test
    fun `fragment href falls back to base href match`() {
        // currentHref has a fragment (subchapter anchor), but segment hrefs are bare chapter hrefs
        assertEquals(1, findActiveSegmentIndex(bookSegments, "chapter2.xhtml#s3"))
        assertEquals(0, findActiveSegmentIndex(bookSegments, "chapter1.xhtml#s1"))
    }

    @Test
    fun `returns 0 when href matches no segment`() {
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unknown.xhtml"))
    }

    @Test
    fun `returns 0 for empty segment list`() {
        assertEquals(0, findActiveSegmentIndex(emptyList(), "chapter1.xhtml"))
    }
}
