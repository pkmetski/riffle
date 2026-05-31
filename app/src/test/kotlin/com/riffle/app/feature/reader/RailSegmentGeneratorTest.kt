package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── buildRailSegments: top-level ──────────────────────────────────────

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
    fun `subchapters are NOT promoted to top level for normally-titled entries`() {
        assertEquals(3, buildRailSegments(toc).size)
    }

    // ── buildRailSegments: blank-title flattening ─────────────────────────

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
        val blank = TocEntry("", "lonely.xhtml")
        assertEquals(
            listOf(RailSegment("", "lonely.xhtml")),
            buildRailSegments(listOf(blank)),
        )
    }

    // ── buildRailSegments: book-title-match flattening ────────────────────

    @Test
    fun `container with title equal to book title is flattened`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val ch2 = TocEntry("Chapter 2", "ch2.xhtml")
        val container = TocEntry("Les Misérables", "container.xhtml", listOf(ch1, ch2))
        val cover = TocEntry("Cover", "cover.xhtml")
        val toc = listOf(cover, container)

        assertEquals(
            listOf(
                RailSegment("Cover", "cover.xhtml"),
                RailSegment("Chapter 1", "ch1.xhtml"),
                RailSegment("Chapter 2", "ch2.xhtml"),
            ),
            buildRailSegments(toc, bookTitle = "Les Misérables"),
        )
    }

    @Test
    fun `container title match is case-insensitive`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val container = TocEntry("Lucky Starr And The Rings Of Saturn", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Chapter 1", "ch1.xhtml")),
            buildRailSegments(listOf(container), bookTitle = "Lucky Starr and the Rings of Saturn"),
        )
    }

    @Test
    fun `container title match is whitespace-insensitive`() {
        val ch1 = TocEntry("Chapter 1", "ch1.xhtml")
        val container = TocEntry("  The   Metamorphosis  ", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Chapter 1", "ch1.xhtml")),
            buildRailSegments(listOf(container), bookTitle = "The Metamorphosis"),
        )
    }

    @Test
    fun `container whose title is a prefix of book title is NOT flattened`() {
        // Guards against "Бай Ганьо тръгна по Европа" being treated as "Бай Ганьо".
        val ch1 = TocEntry("Ch 1", "ch1.xhtml")
        val container = TocEntry("Foo Bar Baz", "container.xhtml", listOf(ch1))
        val segments = buildRailSegments(listOf(container), bookTitle = "Foo")
        assertEquals(1, segments.size)
        assertEquals(RailSegment("Foo Bar Baz", "container.xhtml"), segments[0])
    }

    @Test
    fun `container whose title differs from book title is NOT flattened`() {
        val ch1 = TocEntry("Ch 1", "ch1.xhtml")
        val container = TocEntry("Appendix", "appendix.xhtml", listOf(ch1))
        val other = TocEntry("Chapter 1", "real-ch1.xhtml")
        val toc = listOf(other, container)

        assertEquals(
            listOf(
                RailSegment("Chapter 1", "real-ch1.xhtml"),
                RailSegment("Appendix", "appendix.xhtml"),
            ),
            buildRailSegments(toc, bookTitle = "Some Other Book"),
        )
    }

    @Test
    fun `empty book title disables book-title-match rule`() {
        // A container whose title happens to be "" would still flatten via blank-title rule,
        // but a non-blank container should NOT be flattened when no book title is provided.
        val ch1 = TocEntry("Ch 1", "ch1.xhtml")
        val container = TocEntry("Some Container", "container.xhtml", listOf(ch1))
        val segments = buildRailSegments(listOf(container), bookTitle = "")
        assertEquals(1, segments.size)
        assertEquals("Some Container", segments[0].title)
    }

    @Test
    fun `blank container is flattened even when book title is provided`() {
        val ch1 = TocEntry("Ch 1", "ch1.xhtml")
        val blank = TocEntry("", "container.xhtml", listOf(ch1))
        assertEquals(
            listOf(RailSegment("Ch 1", "ch1.xhtml")),
            buildRailSegments(listOf(blank), bookTitle = "Some Book"),
        )
    }

    @Test
    fun `book-title-match flattening applies recursively for nested redundant containers`() {
        // Outer container with book title, inner container ALSO with book title (unusual but
        // possible). Both should be flattened, exposing the leaves at top level.
        val leaf = TocEntry("Leaf", "leaf.xhtml")
        val inner = TocEntry("My Book", "inner.xhtml", listOf(leaf))
        val outer = TocEntry("My Book", "outer.xhtml", listOf(inner))
        assertEquals(
            listOf(RailSegment("Leaf", "leaf.xhtml")),
            buildRailSegments(listOf(outer), bookTitle = "My Book"),
        )
    }

    // ── buildRailSegments: misc invariants ────────────────────────────────

    @Test
    fun `URL-encoded hrefs are preserved`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val segments = buildRailSegments(listOf(TocEntry("PROLOGUE", encodedHref)))
        assertEquals(1, segments.size)
        assertEquals(encodedHref, segments[0].href)
    }

    @Test
    fun `top-level entries sharing a spine resource collapse to one segment`() {
        // Regression: Asimov "Lucky Starr" books pair each chapter with a subtitle entry at
        // the SAME TOC level, sharing the same spine resource via a fragment:
        //   Chapter 1         → section4.xhtml
        //   The Doomed Ship   → section4.xhtml#heading_id_3
        // The fragment entry can never become the active segment (locator.href has no
        // fragment during natural reading), so it visually "skips" when advancing chapters.
        // The rail should show one segment per spine resource — keep the first, drop the rest.
        val toc = listOf(
            TocEntry("Chapter 1", "section4.xhtml"),
            TocEntry("The Doomed Ship", "section4.xhtml#heading_id_3"),
            TocEntry("Chapter 2", "section5.xhtml"),
            TocEntry("Sub 2", "section5.xhtml#heading_id_3"),
        )
        assertEquals(
            listOf(
                RailSegment("Chapter 1", "section4.xhtml"),
                RailSegment("Chapter 2", "section5.xhtml"),
            ),
            buildRailSegments(toc),
        )
    }

    @Test
    fun `fragment-only top-level entry is kept when no plain-href sibling exists`() {
        // If the only entry pointing to a spine resource has a fragment, keep it — there's
        // nothing to dedup it against, and dropping it would lose the resource entirely.
        val toc = listOf(
            TocEntry("Intro", "section1.xhtml#part1"),
            TocEntry("Chapter 1", "section2.xhtml"),
        )
        assertEquals(
            listOf(
                RailSegment("Intro", "section1.xhtml#part1"),
                RailSegment("Chapter 1", "section2.xhtml"),
            ),
            buildRailSegments(toc),
        )
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
    fun `returns 0 when href matches no segment and no spine info`() {
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unknown.xhtml"))
    }

    @Test
    fun `unmatched href falls back to preceding chapter by spine order, not chapter 0`() {
        // Spine has an intermezzo resource between chapter2 and chapter3 with no TOC entry.
        // Without spine awareness the chapter label would flicker to "Chapter 1" when the
        // navigator emits a locator for the intermezzo.
        val spine = listOf(
            "chapter1.xhtml",
            "chapter2.xhtml",
            "intermezzo.xhtml",
            "chapter3.xhtml",
        )
        assertEquals(1, findActiveSegmentIndex(bookSegments, "intermezzo.xhtml", spine))
    }

    @Test
    fun `unmatched href before first mapped segment returns 0`() {
        // A pre-chapter1 resource (e.g. front-matter) with no TOC entry should map to 0.
        val spine = listOf("frontmatter.xhtml", "chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml")
        assertEquals(0, findActiveSegmentIndex(bookSegments, "frontmatter.xhtml", spine))
    }

    @Test
    fun `unmatched href after last mapped segment returns last segment`() {
        val spine = listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml", "appendix.xhtml")
        assertEquals(2, findActiveSegmentIndex(bookSegments, "appendix.xhtml", spine))
    }

    @Test
    fun `unmatched href not in spine returns 0`() {
        val spine = listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml")
        assertEquals(0, findActiveSegmentIndex(bookSegments, "unrelated.xhtml", spine))
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

    // ── weightSegmentsByChapterLength ─────────────────────────────────────

    @Test
    fun `weights match spine position counts for one-to-one mapping`() {
        val segs = listOf(
            RailSegment("A", "a.xhtml"),
            RailSegment("B", "b.xhtml"),
            RailSegment("C", "c.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml", "b.xhtml", "c.xhtml"),
            positionCounts = listOf(10, 30, 60),
        )
        assertEquals(10f, weighted[0].weight, 0f)
        assertEquals(30f, weighted[1].weight, 0f)
        assertEquals(60f, weighted[2].weight, 0f)
    }

    @Test
    fun `weights split equally when multiple segments share one spine resource`() {
        val segs = listOf(
            RailSegment("1.1", "chapter1.xhtml#s1"),
            RailSegment("1.2", "chapter1.xhtml#s2"),
            RailSegment("2", "chapter2.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("chapter1.xhtml", "chapter2.xhtml"),
            positionCounts = listOf(40, 20),
        )
        assertEquals(20f, weighted[0].weight, 0f)
        assertEquals(20f, weighted[1].weight, 0f)
        assertEquals(20f, weighted[2].weight, 0f)
    }

    @Test
    fun `segments without spine match fall back to weight 1`() {
        val segs = listOf(
            RailSegment("A", "a.xhtml"),
            RailSegment("Unknown", "missing.xhtml"),
        )
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml"),
            positionCounts = listOf(50),
        )
        assertEquals(50f, weighted[0].weight, 0f)
        assertEquals(1f, weighted[1].weight, 0f)
    }

    @Test
    fun `empty position list leaves weights at default 1`() {
        val segs = listOf(RailSegment("A", "a.xhtml"), RailSegment("B", "b.xhtml"))
        val weighted = weightSegmentsByChapterLength(
            segs,
            spineHrefs = listOf("a.xhtml", "b.xhtml"),
            positionCounts = emptyList(),
        )
        assertEquals(1f, weighted[0].weight, 0f)
        assertEquals(1f, weighted[1].weight, 0f)
    }

    // ── railSegmentBounds ─────────────────────────────────────────────────

    @Test
    fun `bounds widths are proportional to weights`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        val bounds = railSegmentBounds(segs, totalWidth = 1000f)
        assertEquals(0f, bounds[0].first, 0.001f)
        assertEquals(100f, bounds[0].second, 0.001f)
        assertEquals(100f, bounds[1].first, 0.001f)
        assertEquals(300f, bounds[1].second, 0.001f)
        assertEquals(400f, bounds[2].first, 0.001f)
        assertEquals(600f, bounds[2].second, 0.001f)
    }

    @Test
    fun `bounds widths sum exactly to total width with no overlap or gap`() {
        // Pathological weights designed to expose accumulated FP drift.
        val segs = (1..17).map { RailSegment("c$it", "c$it.xhtml", weight = it.toFloat() * 1.7f) }
        val total = 1080f
        val bounds = railSegmentBounds(segs, totalWidth = total)
        // Sum of widths == total (within fp tolerance)
        val sumWidths = bounds.sumOf { it.second.toDouble() }
        assertEquals(total.toDouble(), sumWidths, 0.0005)
        // Adjacent segments touch exactly: bounds[i].end == bounds[i+1].start
        for (i in 0 until bounds.size - 1) {
            val endI = bounds[i].first + bounds[i].second
            assertEquals("segment $i end != segment ${i + 1} start", bounds[i + 1].first, endI, 0f)
        }
        // First starts at 0, last ends at total.
        assertEquals(0f, bounds.first().first, 0f)
        assertEquals(total, bounds.last().first + bounds.last().second, 0.0005f)
    }

    @Test
    fun `equal weights fall back when total weight is zero`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 0f),
            RailSegment("B", "b", weight = 0f),
        )
        val bounds = railSegmentBounds(segs, totalWidth = 100f)
        // With zero total, neutral fallback should still produce a valid layout summing to total.
        val sum = bounds.sumOf { it.second.toDouble() }
        assertEquals(100.0, sum, 0.0005)
    }

    @Test
    fun `empty segments produces empty bounds`() {
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(emptyList(), 1000f))
    }

    @Test
    fun `zero or negative total width produces empty bounds`() {
        val segs = listOf(RailSegment("A", "a"))
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(segs, 0f))
        assertEquals(emptyList<Pair<Float, Float>>(), railSegmentBounds(segs, -10f))
    }

    // ── weightedRailCursorPosition ────────────────────────────────────────

    @Test
    fun `cursor at progression 0 sits at active segment left edge`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        // Segment 1 ("B") covers [10/100, 40/100] = [0.1, 0.4]
        assertEquals(0.1f, weightedRailCursorPosition(1, segs, 0f), 0.0001f)
    }

    @Test
    fun `cursor at progression 1 sits at active segment right edge`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        assertEquals(0.4f, weightedRailCursorPosition(1, segs, 1f), 0.0001f)
    }

    @Test
    fun `cursor at progression 0_5 sits at active segment midpoint`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        assertEquals(0.25f, weightedRailCursorPosition(1, segs, 0.5f), 0.0001f)
    }

    @Test
    fun `cursor always stays within active segment bounds for any weights`() {
        // Cover several weight distributions, including extreme imbalance.
        val weightSets: List<List<Float>> = listOf(
            listOf(1f, 1f, 1f, 1f),
            listOf(1f, 50f, 1f, 50f),
            listOf(100f, 1f, 100f, 1f),
            listOf(7f, 13f, 23f, 41f, 59f),
        )
        for (weights in weightSets) {
            val segs = weights.mapIndexed { i, w -> RailSegment("$i", "c$i.xhtml", weight = w) }
            val bounds = railSegmentBounds(segs, totalWidth = 1f)
            for (active in segs.indices) {
                val (left, width) = bounds[active]
                val right = left + width
                for (p in listOf(0f, 0.001f, 0.25f, 0.5f, 0.75f, 0.999f, 1f)) {
                    val cursor = weightedRailCursorPosition(active, segs, p)
                    assertTrue(
                        "weights=$weights active=$active p=$p cursor=$cursor not in [$left,$right]",
                        cursor in left..right,
                    )
                }
            }
        }
    }

    @Test
    fun `cursor returns 0 for empty segments`() {
        assertEquals(0f, weightedRailCursorPosition(0, emptyList(), 0.5f), 0f)
    }

    @Test
    fun `cursor clamps out-of-range active index`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
        )
        // activeIndex past end → clamped to last segment ("B" at [0.25, 1.0])
        assertEquals(0.25f, weightedRailCursorPosition(99, segs, 0f), 0.0001f)
        assertEquals(1.0f, weightedRailCursorPosition(99, segs, 1f), 0.0001f)
        // activeIndex negative → clamped to first ("A" at [0.0, 0.25])
        assertEquals(0f, weightedRailCursorPosition(-3, segs, 0f), 0.0001f)
        assertEquals(0.25f, weightedRailCursorPosition(-3, segs, 1f), 0.0001f)
    }

    // ── railSegmentIndexAt (tap hit-test) ─────────────────────────────────

    @Test
    fun `tap hit-test returns segment containing the x position`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 10f),
            RailSegment("B", "b", weight = 30f),
            RailSegment("C", "c", weight = 60f),
        )
        // Width 100 → segment boundaries at 10, 40
        assertEquals(0, railSegmentIndexAt(segs, 0f, 100f))
        assertEquals(0, railSegmentIndexAt(segs, 9.99f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 10.01f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 39.99f, 100f))
        assertEquals(2, railSegmentIndexAt(segs, 40.01f, 100f))
        assertEquals(2, railSegmentIndexAt(segs, 100f, 100f))
    }

    @Test
    fun `tap hit-test clamps out-of-range x to the nearest end`() {
        val segs = listOf(
            RailSegment("A", "a", weight = 1f),
            RailSegment("B", "b", weight = 1f),
        )
        assertEquals(0, railSegmentIndexAt(segs, -50f, 100f))
        assertEquals(1, railSegmentIndexAt(segs, 9999f, 100f))
    }

    @Test
    fun `tap hit-test on empty segments returns -1`() {
        assertEquals(-1, railSegmentIndexAt(emptyList(), 5f, 100f))
    }
}
