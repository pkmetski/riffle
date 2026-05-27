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

    // ── buildRailSegments: null href (no expansion) ───────────────────────

    @Test
    fun `null href returns one segment per top-level entry`() {
        val segments = buildRailSegments(toc, null)
        assertEquals(3, segments.size)
        assertEquals(RailSegment("Chapter 1", "chapter1.xhtml"), segments[0])
        assertEquals(RailSegment("Chapter 2", "chapter2.xhtml"), segments[1])
        assertEquals(RailSegment("Chapter 3", "chapter3.xhtml"), segments[2])
    }

    @Test
    fun `empty toc returns empty list`() {
        assertEquals(emptyList<RailSegment>(), buildRailSegments(emptyList(), null))
    }

    // ── buildRailSegments: active entry is a leaf (no children) ───────────

    @Test
    fun `active leaf entry does not expand anything`() {
        val segments = buildRailSegments(toc, "chapter3.xhtml")
        assertEquals(3, segments.size)
        assertEquals(RailSegment("Chapter 1", "chapter1.xhtml"), segments[0])
        assertEquals(RailSegment("Chapter 2", "chapter2.xhtml"), segments[1])
        assertEquals(RailSegment("Chapter 3", "chapter3.xhtml"), segments[2])
    }

    // ── buildRailSegments: active entry has children (expand one level) ───

    @Test
    fun `active entry with children expands to show its children`() {
        val segments = buildRailSegments(toc, "chapter1.xhtml#s1")
        assertEquals(
            listOf(
                RailSegment("1.1", "chapter1.xhtml#s1"),
                RailSegment("1.2", "chapter1.xhtml#s2"),
                RailSegment("Chapter 2", "chapter2.xhtml"),
                RailSegment("Chapter 3", "chapter3.xhtml"),
            ),
            segments,
        )
    }

    @Test
    fun `active entry expands chapter 2 when href is in chapter 2`() {
        val segments = buildRailSegments(toc, "chapter2.xhtml#s3")
        assertEquals(
            listOf(
                RailSegment("Chapter 1", "chapter1.xhtml"),
                RailSegment("2.1", "chapter2.xhtml#s1"),
                RailSegment("2.2", "chapter2.xhtml#s2"),
                RailSegment("2.3", "chapter2.xhtml#s3"),
                RailSegment("Chapter 3", "chapter3.xhtml"),
            ),
            segments,
        )
    }

    // ── buildRailSegments: base-href fallback ─────────────────────────────

    @Test
    fun `base-href match expands parent when exact href not in tree`() {
        // "chapter1.xhtml#unknown" is not an exact match, but base matches chapter1
        val segments = buildRailSegments(toc, "chapter1.xhtml#unknown")
        assertEquals(
            listOf(
                RailSegment("1.1", "chapter1.xhtml#s1"),
                RailSegment("1.2", "chapter1.xhtml#s2"),
                RailSegment("Chapter 2", "chapter2.xhtml"),
                RailSegment("Chapter 3", "chapter3.xhtml"),
            ),
            segments,
        )
    }

    // ── buildRailSegments: recursive expansion (depth > 1) ────────────────

    @Test
    fun `recursive expansion follows the active path through multiple levels`() {
        val sub1 = TocEntry("Sub 1", "part1-ch1-sub1.xhtml")
        val sub2 = TocEntry("Sub 2", "part1-ch1-sub2.xhtml")
        val ch1 = TocEntry("Ch 1", "part1-ch1.xhtml", listOf(sub1, sub2))
        val ch2 = TocEntry("Ch 2", "part1-ch2.xhtml")
        val part1 = TocEntry("Part 1", "part1.xhtml", listOf(ch1, ch2))
        val part2 = TocEntry("Part 2", "part2.xhtml")
        val deepToc = listOf(part1, part2)

        val segments = buildRailSegments(deepToc, "part1-ch1-sub2.xhtml")
        assertEquals(
            listOf(
                RailSegment("Sub 1", "part1-ch1-sub1.xhtml"),
                RailSegment("Sub 2", "part1-ch1-sub2.xhtml"),
                RailSegment("Ch 2", "part1-ch2.xhtml"),
                RailSegment("Part 2", "part2.xhtml"),
            ),
            segments,
        )
    }

    @Test
    fun `recursive expansion at intermediate level shows children of active branch only`() {
        val sub1 = TocEntry("Sub 1", "part1-ch1-sub1.xhtml")
        val ch1 = TocEntry("Ch 1", "part1-ch1.xhtml", listOf(sub1))
        val ch2 = TocEntry("Ch 2", "part1-ch2.xhtml")
        val part1 = TocEntry("Part 1", "part1.xhtml", listOf(ch1, ch2))
        val part2 = TocEntry("Part 2", "part2.xhtml")
        val deepToc = listOf(part1, part2)

        // Active href is ch2 (sibling of ch1, child of part1)
        // Part1 is on active path -> expand to [ch1, ch2]. Ch2 is a leaf -> no further expansion.
        val segments = buildRailSegments(deepToc, "part1-ch2.xhtml")
        assertEquals(
            listOf(
                RailSegment("Ch 1", "part1-ch1.xhtml"),
                RailSegment("Ch 2", "part1-ch2.xhtml"),
                RailSegment("Part 2", "part2.xhtml"),
            ),
            segments,
        )
    }

    // ── buildRailSegments: blank-titled entry always expands ──────────────

    @Test
    fun `blank-titled entry expands its children regardless of active href`() {
        val story1 = TocEntry("Story 1", "story1.xhtml")
        val story2 = TocEntry("Story 2", "story2.xhtml")
        val blank = TocEntry("  \n ", "container.xhtml", listOf(story1, story2))
        val cover = TocEntry("Cover", "cover.xhtml")
        val credits = TocEntry("Credits", "credits.xhtml")
        val toc = listOf(cover, blank, credits)

        // Active is on cover, NOT inside the blank entry — but it still expands
        val segments = buildRailSegments(toc, "cover.xhtml")
        assertEquals(
            listOf(
                RailSegment("Cover", "cover.xhtml"),
                RailSegment("Story 1", "story1.xhtml"),
                RailSegment("Story 2", "story2.xhtml"),
                RailSegment("Credits", "credits.xhtml"),
            ),
            segments,
        )
    }

    // ── buildRailSegments: URL-encoded hrefs ──────────────────────────────

    @Test
    fun `URL-encoded hrefs are preserved in rail segments`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val toc = listOf(TocEntry("PROLOGUE", encodedHref))
        val segments = buildRailSegments(toc, null)
        assertEquals(1, segments.size)
        assertEquals(encodedHref, segments[0].href)
    }

    @Test
    fun `segments with duplicate chapter titles but unique hrefs are all retained`() {
        val toc = listOf(
            TocEntry("EDDARD", "chapter_eddard1.xhtml"),
            TocEntry("EDDARD", "chapter_eddard2.xhtml"),
            TocEntry("EDDARD", "chapter_eddard3.xhtml"),
        )
        val segments = buildRailSegments(toc, null)
        assertEquals(3, segments.size)
        assertEquals("chapter_eddard1.xhtml", segments[0].href)
        assertEquals("chapter_eddard2.xhtml", segments[1].href)
        assertEquals("chapter_eddard3.xhtml", segments[2].href)
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
    fun `single-chapter book returns one segment`() {
        val toc = listOf(TocEntry("Only Chapter", "only.xhtml"))
        val segments = buildRailSegments(toc, null)
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Only Chapter", "only.xhtml"), segments[0])
    }
}
