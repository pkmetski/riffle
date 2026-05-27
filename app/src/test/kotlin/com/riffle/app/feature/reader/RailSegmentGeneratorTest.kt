package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class RailSegmentGeneratorTest {

    // ── Test data ─────────────────────────────────────────────────────────

    private val section11 = TocEntry("1.1", "chapter1.xhtml#s1")
    private val section12 = TocEntry("1.2", "chapter1.xhtml#s2")
    private val chapter1 = TocEntry("Chapter 1", "chapter1.xhtml", listOf(section11, section12))

    private val section21 = TocEntry("2.1", "chapter2.xhtml#s1")
    private val section22 = TocEntry("2.2", "chapter2.xhtml#s2")
    private val section23 = TocEntry("2.3", "chapter2.xhtml#s3")
    private val chapter2 = TocEntry("Chapter 2", "chapter2.xhtml", listOf(section21, section22, section23))

    private val chapter3 = TocEntry("Chapter 3", "chapter3.xhtml")
    private val toc = listOf(chapter1, chapter2, chapter3)

    // ── buildRailSegments ─────────────────────────────────────────────────

    @Test
    fun `returns one segment per top-level entry`() {
        val segments = buildRailSegments(toc)
        assertEquals(
            listOf(
                RailSegment("Chapter 1", "chapter1.xhtml"),
                RailSegment("Chapter 2", "chapter2.xhtml"),
                RailSegment("Chapter 3", "chapter3.xhtml"),
            ),
            segments,
        )
    }

    @Test
    fun `empty toc returns empty list`() {
        assertEquals(emptyList<RailSegment>(), buildRailSegments(emptyList()))
    }

    @Test
    fun `blank-titled entry is replaced by its children at top level`() {
        val story1 = TocEntry("Story 1", "story1.xhtml")
        val story2 = TocEntry("Story 2", "story2.xhtml")
        val blank = TocEntry("  \n ", "container.xhtml", listOf(story1, story2))
        val cover = TocEntry("Cover", "cover.xhtml")
        val credits = TocEntry("Credits", "credits.xhtml")
        val toc = listOf(cover, blank, credits)

        assertEquals(
            listOf(
                RailSegment("Cover", "cover.xhtml"),
                RailSegment("Story 1", "story1.xhtml"),
                RailSegment("Story 2", "story2.xhtml"),
                RailSegment("Credits", "credits.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `empty title entry is replaced by its children at top level`() {
        val story1 = TocEntry("Story 1", "story1.xhtml")
        val blank = TocEntry("", "container.xhtml", listOf(story1))
        assertEquals(
            listOf(RailSegment("Story 1", "story1.xhtml")),
            buildRailSegments(listOf(blank)),
        )
    }

    @Test
    fun `nested blank-titled entries are recursively expanded at top level`() {
        val leaf = TocEntry("Leaf", "leaf.xhtml")
        val innerBlank = TocEntry("", "inner.xhtml", listOf(leaf))
        val outerBlank = TocEntry("  ", "outer.xhtml", listOf(innerBlank))
        val toc = listOf(outerBlank, TocEntry("Other", "other.xhtml"))

        assertEquals(
            listOf(
                RailSegment("Leaf", "leaf.xhtml"),
                RailSegment("Other", "other.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `blank-titled leaf entry is kept as a segment with blank title`() {
        // Edge case: blank-titled but no children — keep as-is, don't lose it.
        val blank = TocEntry("", "lonely.xhtml")
        assertEquals(
            listOf(RailSegment("", "lonely.xhtml")),
            buildRailSegments(listOf(blank)),
        )
    }

    @Test
    fun `URL-encoded hrefs are preserved`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val segments = buildRailSegments(listOf(TocEntry("PROLOGUE", encodedHref)))
        assertEquals(1, segments.size)
        assertEquals(encodedHref, segments[0].href)
    }

    @Test
    fun `segments with duplicate titles but unique hrefs are all retained`() {
        val toc = listOf(
            TocEntry("EDDARD", "chapter_eddard1.xhtml"),
            TocEntry("EDDARD", "chapter_eddard2.xhtml"),
            TocEntry("EDDARD", "chapter_eddard3.xhtml"),
        )
        val segments = buildRailSegments(toc)
        assertEquals(3, segments.size)
        assertEquals("chapter_eddard1.xhtml", segments[0].href)
        assertEquals("chapter_eddard2.xhtml", segments[1].href)
        assertEquals("chapter_eddard3.xhtml", segments[2].href)
    }

    @Test
    fun `subchapters are NOT promoted to top level for normally-titled entries`() {
        assertEquals(3, buildRailSegments(toc).size)
    }

    // ── findActiveSegmentIndex ─────────────────────────────────────────────

    private val bookSegments = listOf(
        RailSegment("Chapter 1: The Beginning", "chapter1.xhtml"),
        RailSegment("Chapter 2: The Middle", "chapter2.xhtml"),
        RailSegment("Chapter 3: The End", "chapter3.xhtml"),
    )

    @Test
    fun `exact href match selects correct chapter`() {
        assertEquals(2, findActiveSegmentIndex(bookSegments, "chapter3.xhtml"))
    }

    @Test
    fun `fragment href falls back to base href match`() {
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

    @Test
    fun `single-chapter book returns one segment`() {
        val toc = listOf(TocEntry("Only Chapter", "only.xhtml"))
        val segments = buildRailSegments(toc)
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Only Chapter", "only.xhtml"), segments[0])
    }
}
