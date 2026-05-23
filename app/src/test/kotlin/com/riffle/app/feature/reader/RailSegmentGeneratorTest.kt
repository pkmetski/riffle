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

    @Test
    fun `URL-encoded hrefs are preserved in rail segments`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val toc = listOf(TocEntry("PROLOGUE", encodedHref))
        val segments = buildRailSegments(toc)
        assertEquals(1, segments.size)
        assertEquals(encodedHref, segments[0].href)
    }

    @Test
    fun `findActiveSegmentIndex with URL-encoded href exact match`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val segments = listOf(
            RailSegment("Title Page", "Text/titlepage.xhtml"),
            RailSegment("PROLOGUE", encodedHref),
        )
        assertEquals(1, findActiveSegmentIndex(segments, encodedHref))
    }

    @Test
    fun `findActiveSegmentIndex with URL-encoded href and fragment fallback`() {
        val encodedHref = "Text/chapter1%20special.xhtml"
        val segments = listOf(RailSegment("Chapter 1", encodedHref))
        assertEquals(0, findActiveSegmentIndex(segments, "$encodedHref#s1"))
    }

    @Test
    fun `segments with duplicate chapter titles but unique hrefs are all retained`() {
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
    fun `single-chapter book returns one segment`() {
        val toc = listOf(TocEntry("Only Chapter", "only.xhtml"))
        val segments = buildRailSegments(toc)
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Only Chapter", "only.xhtml"), segments[0])
    }
}
