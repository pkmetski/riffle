package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RailSegmentGeneratorTest {

    private val sec21 = TocEntry("Section 2.1: Rising Action", "chapter2.xhtml#s1")
    private val sec22 = TocEntry("Section 2.2: Conflict",      "chapter2.xhtml#s2")
    private val sec23 = TocEntry("Section 2.3: Turning Point", "chapter2.xhtml#s3")

    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml",
        listOf(TocEntry("1.1", "chapter1.xhtml#s1"), TocEntry("1.2", "chapter1.xhtml#s2")))
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml", listOf(sec21, sec22, sec23))
    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments ──────────────────────────────────────────────────

    @Test
    fun `segments for chapter with subchapters returns children`() {
        val segments = buildRailSegments(toc, "chapter2.xhtml#s2")
        assertEquals(listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
            RailSegment("Section 2.3: Turning Point", "chapter2.xhtml#s3"),
        ), segments)
    }

    @Test
    fun `segments for chapter with no subchapters returns single segment`() {
        val segments = buildRailSegments(toc, "chapter3.xhtml")
        assertEquals(listOf(RailSegment("Chapter 3", "chapter3.xhtml")), segments)
    }

    @Test
    fun `segments when href is chapter root (no fragment)`() {
        val segments = buildRailSegments(toc, "chapter2.xhtml")
        assertEquals(3, segments.size)
    }

    @Test
    fun `segments for unknown href returns empty`() {
        val segments = buildRailSegments(toc, "unknown.xhtml")
        assertEquals(emptyList<RailSegment>(), segments)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    @Test
    fun `active segment for exact href match`() {
        val segments = listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
            RailSegment("Section 2.3: Turning Point", "chapter2.xhtml#s3"),
        )
        assertEquals(2, findActiveSegmentIndex(segments, "chapter2.xhtml#s3"))
    }

    @Test
    fun `active segment defaults to 0 when no exact match`() {
        val segments = listOf(
            RailSegment("Section 2.1: Rising Action", "chapter2.xhtml#s1"),
            RailSegment("Section 2.2: Conflict",      "chapter2.xhtml#s2"),
        )
        assertEquals(0, findActiveSegmentIndex(segments, "chapter2.xhtml"))
    }

    @Test
    fun `active segment for single segment list is always 0`() {
        val segments = listOf(RailSegment("Chapter 3", "chapter3.xhtml"))
        assertEquals(0, findActiveSegmentIndex(segments, "chapter3.xhtml"))
    }

    @Test
    fun `active segment for empty list returns 0`() {
        assertEquals(0, findActiveSegmentIndex(emptyList(), "chapter3.xhtml"))
    }
}
