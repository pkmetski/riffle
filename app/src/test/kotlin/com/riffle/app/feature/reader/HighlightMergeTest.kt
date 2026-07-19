package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for auto-merging adjacent highlights (spec: 2026-07-05-highlight-auto-merge-design.md).
 *
 * These assertions would flip red if the merge rules were reverted line-for-line — see individual
 * doc comments for which line of the design each pins.
 */
class HighlightMergeTest {

    private fun annotation(
        id: String = "n",
        color: String = "yellow",
        note: String? = null,
        textSnippet: String = "",
        textBefore: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
        progression: Double = 0.0,
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
    ) = Annotation(
        id = id,
        sourceId = "srv",
        itemId = "item",
        type = type,
        cfi = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        color = color,
        note = note,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        chapterHref = "ch1.xhtml",
        spineIndex = spineIndex,
        progression = progression,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun anchor(
        color: String = "yellow",
        note: String? = null,
        textSnippet: String,
        textBefore: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
        progression: Double = 0.5,
    ) = MergeAnchor(
        spineIndex = spineIndex,
        color = color,
        note = note,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        progression = progression,
        chapterHref = "ch1.xhtml",
    )

    // -------- Eligibility (pins design rule: same colour, both notes empty, same chapter) --------

    @Test
    fun `same-colour no-note in same chapter is eligible`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(id = "b", color = "yellow", textSnippet = "Bar")
        assertTrue(isMergeEligible(a, n))
    }

    @Test
    fun `different colours are not eligible`() {
        val a = anchor(color = "yellow", textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(color = "blue", textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `anchor with a note is not eligible`() {
        val a = anchor(note = "Look at this", textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `candidate with a note is not eligible`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(note = "Interesting", textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `blank note counts as empty`() {
        val a = anchor(note = "   ", textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(note = "\n\t", textSnippet = "Bar")
        assertTrue(isMergeEligible(a, n))
    }

    @Test
    fun `different chapters are not eligible`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar", spineIndex = 3)
        val n = annotation(textSnippet = "Bar", spineIndex = 4)
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `bookmarks are not eligible`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(textSnippet = "Bar", type = AnnotationEntity.TYPE_BOOKMARK)
        assertEquals(false, isMergeEligible(a, n))
    }

    // -------- Adjacency (pins design rule: textAfter/textBefore contain neighbour snippet) --------

    @Test
    fun `adjacent after with single space collapses whitespace`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar and more")
        val n = annotation(textSnippet = "Bar")
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_AFTER_ANCHOR, match!!.side)
        assertEquals(" ", match.whitespaceBetween)
    }

    @Test
    fun `adjacent before with single space collapses whitespace`() {
        val a = anchor(textSnippet = "Bar", textBefore = "Something Foo ")
        val n = annotation(textSnippet = "Foo")
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_BEFORE_ANCHOR, match!!.side)
        assertEquals(" ", match.whitespaceBetween)
    }

    @Test
    fun `strict abutment matches with empty whitespace run`() {
        val a = anchor(textSnippet = "Foo", textAfter = "Bar and more")
        val n = annotation(textSnippet = "Bar")
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals("", match!!.whitespaceBetween)
    }

    @Test
    fun `gap with non-whitespace chars is not adjacent`() {
        val a = anchor(textSnippet = "Foo", textAfter = ", Bar and more")
        val n = annotation(textSnippet = "Bar")
        assertNull(findAdjacency(a, n))
    }

    @Test
    fun `neighbour must sit at start of textAfter not further into it`() {
        val a = anchor(textSnippet = "Foo", textAfter = " middle Bar")
        val n = annotation(textSnippet = "Bar")
        assertNull(findAdjacency(a, n))
    }

    @Test
    fun `blank snippets never match`() {
        val a = anchor(textSnippet = "  ", textAfter = "Bar")
        val n = annotation(textSnippet = "Bar")
        assertNull(findAdjacency(a, n))
    }

    // -------- Merge action (pins design rule for merged-field computation) --------

    @Test
    fun `after-merge combines snippets with whitespace and inherits candidate textAfter`() {
        val a = anchor(
            textSnippet = "Foo",
            textBefore = "Before ",
            textAfter = " Bar and continues",
            progression = 0.3,
        )
        val n = annotation(textSnippet = "Bar", textAfter = "and continues", progression = 0.5)
        val match = findAdjacency(a, n)!!
        val merged = applyMerge(a, match)
        assertEquals("Foo Bar", merged.textSnippet)
        assertEquals("Before ", merged.textBefore)
        assertEquals("and continues", merged.textAfter)
        assertEquals(0.3, merged.progression, 1e-9)
    }

    @Test
    fun `before-merge inherits candidate textBefore and progression`() {
        val a = anchor(
            textSnippet = "Bar",
            textBefore = "Prev Foo ",
            textAfter = "After",
            progression = 0.5,
        )
        val n = annotation(textSnippet = "Foo", textBefore = "Prev ", progression = 0.4)
        val match = findAdjacency(a, n)!!
        val merged = applyMerge(a, match)
        assertEquals("Foo Bar", merged.textSnippet)
        assertEquals("Prev ", merged.textBefore)
        assertEquals("After", merged.textAfter)
        assertEquals(0.4, merged.progression, 1e-9)
    }

    // -------- Chain merge (pins loop-until-stable) --------

    @Test
    fun `chain merge absorbs both neighbours across two passes`() {
        // Layout: [L] "Foo" [A] "Bar" [R] where A is the anchor and there are two same-colour
        // no-note neighbours on either side.
        var a = anchor(
            textSnippet = "Bar",
            textBefore = "Foo ",
            textAfter = " Baz",
            progression = 0.5,
        )
        val left = annotation(id = "L", textSnippet = "Foo", textBefore = "", textAfter = " Bar Baz", progression = 0.4)
        val right = annotation(id = "R", textSnippet = "Baz", textBefore = "Foo Bar ", textAfter = "", progression = 0.6)
        val pool = listOf(left, right)

        val consumed = mutableSetOf<String>()
        var iterations = 0
        while (true) {
            val m = findAnyMergeableNeighbor(a, pool, consumed) ?: break
            consumed += m.neighbor.id
            a = applyMerge(a, m)
            iterations++
            check(iterations < 10)
        }
        assertEquals(2, consumed.size)
        assertEquals(setOf("L", "R"), consumed)
        assertEquals("Foo Bar Baz", a.textSnippet)
        assertEquals("", a.textBefore)
        assertEquals("", a.textAfter)
        assertEquals(0.4, a.progression, 1e-9)
    }

    @Test
    fun `excluded id is skipped even if otherwise eligible`() {
        val a = anchor(textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(id = "self", textSnippet = "Bar")
        assertNull(findAnyMergeableNeighbor(a, listOf(n), excludeIds = setOf("self")))
    }

    /**
     * Readium bounds textAfter/textBefore to a fixed window; a long neighbour snippet may not fit
     * in the anchor's textAfter but does fit in the neighbour's own textBefore. Adjacency must
     * still be detected via the neighbour's context.
     */
    @Test
    fun `adjacency detected via neighbours textBefore when anchor textAfter is truncated`() {
        val a = anchor(textSnippet = "Foo", textAfter = "") // truncated / unavailable
        val n = annotation(
            textSnippet = "Bar",
            textBefore = "Some earlier prose Foo ",
        )
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_AFTER_ANCHOR, match!!.side)
    }

    @Test
    fun `adjacency detected via neighbours textAfter when anchor textBefore is truncated`() {
        val a = anchor(textSnippet = "Bar", textBefore = "")
        val n = annotation(
            textSnippet = "Foo",
            textAfter = " Bar rest of paragraph",
        )
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_BEFORE_ANCHOR, match!!.side)
    }

    /**
     * Reproduces the on-device miss from the 2026-07-05 note-clear scenario: anchor snippet is
     * ~30 chars, candidate.textAfter starts with " " + <the exact anchor snippet>, but Readium
     * emitted an NBSP (U+00A0) as one of the interior spaces in candidate.textAfter — a byte-exact
     * regionMatches misses; whitespace-normalised match catches it.
     */
    @Test
    fun `adjacency tolerates NBSP-vs-space in captured DOM whitespace`() {
        val a = anchor(textSnippet = "as a new feature or a bug fix.", textBefore = "such ")
        // Same words, but the space between "new" and "feature" is a non-breaking space.
        val nbspVariant = " as a new feature or a bug fix. At first"
        val n = annotation(textSnippet = "Most programmers approach software", textAfter = nbspVariant)
        assertNotNull(findAdjacency(a, n))
    }

    /**
     * Reproduces the 2026-07-05 scenario more directly: candidate.textAfter begins with the
     * anchor's full snippet plus trailing prose, and the anchor's textBefore is short — the
     * bidirectional check MUST catch it via the candidate's own textAfter.
     */
    @Test
    fun `adjacency detected via neighbours textAfter even when anchor textBefore does not carry it`() {
        val a = anchor(
            textSnippet = "as a new feature or a bug fix.",
            textBefore = "o get something working, such ",
            textAfter = "\nThe problem with tactical pro",
        )
        val n = annotation(
            textSnippet = "Most programmers approach software design as a tactical activity",
            textBefore = "the long run.\n3.1  Tactical programming\n",
            textAfter = " as a new feature or a bug fix. At first",
        )
        val match = findAdjacency(a, n)
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_BEFORE_ANCHOR, match!!.side)
    }

    @Test
    fun `color match is case-insensitive`() {
        val a = anchor(color = "YELLOW", textSnippet = "Foo", textAfter = " Bar")
        val n = annotation(color = "yellow", textSnippet = "Bar")
        assertTrue(isMergeEligible(a, n))
    }

    // -------- Cross-figure merge (revised 2026-07-10): reject when a figure sits between --------

    /**
     * Two same-colour no-note text highlights on either side of a figure MUST NOT be mergeable
     * (revised rule 2026-07-10). Void figures contribute zero readable chars, so [findAdjacency]'s
     * textual check would report "adjacent"; [hasFigureInGap] catches the figure via the DOM walk
     * and rejects the merge in [findAnyMergeableNeighbor]. Rationale: two separately-created
     * highlights represent independent user actions — merging them would silently annotate the
     * figure between them. This assertion would flip red if the figure-gap check regressed.
     */
    @Test
    fun `same-colour text highlights on either side of a figure are NOT merged when html shows a figure in the gap`() {
        val html = """
            <html><body>
              <p>To characterize this in a crude mathematical way:</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>The overall complexity of a system.</p>
            </body></html>
        """.trimIndent()
        val anchor = anchor(
            textSnippet = "The overall complexity of a system.",
            textBefore = "way:",
            textAfter = "",
        )
        val neighbor = annotation(
            id = "before-fig",
            textSnippet = "To characterize this in a crude mathematical way:",
            textAfter = "The overall complexity of a system",
        )
        val textOnlyMatch = findAdjacency(anchor, neighbor)
        assertNotNull("text stream still reports adjacency — figure is void", textOnlyMatch)
        val htmlAwareMatch = findAnyMergeableNeighbor(
            anchor,
            listOf(neighbor),
            excludeIds = emptySet(),
            html = html,
        )
        assertNull(
            "with html available, a figure in the gap must block the merge",
            htmlAwareMatch,
        )
    }

    /**
     * When no figure sits between the two ranges, [hasFigureInGap] returns false and the merge
     * proceeds as before. Guards against over-eager rejection.
     */
    @Test
    fun `text highlights with no figure between still merge when html is provided`() {
        val html = """
            <html><body>
              <p>To characterize this in a crude mathematical way:The overall complexity of a system.</p>
            </body></html>
        """.trimIndent()
        val anchor = anchor(
            textSnippet = "The overall complexity of a system.",
            textBefore = "way:",
            textAfter = "",
        )
        val neighbor = annotation(
            id = "adjacent",
            textSnippet = "To characterize this in a crude mathematical way:",
            textAfter = "The overall complexity of a system",
        )
        val match = findAnyMergeableNeighbor(
            anchor,
            listOf(neighbor),
            excludeIds = emptySet(),
            html = html,
        )
        assertNotNull("no figure in the gap → merge should still happen", match)
        assertEquals(MergeSide.CANDIDATE_BEFORE_ANCHOR, match!!.side)
    }

    // -------- Standalone TYPE_IMAGE candidates are never auto-merged (revised 2026-07-10) --------

    /**
     * A standalone TYPE_IMAGE annotation (long-press-created) is NOT eligible for auto-merge into
     * an adjacent text highlight. Under the revised rule, the figure only becomes part of a
     * highlight when the user's single selection covers text on both sides of the figure at
     * creation time; long-press-created figure annotations represent distinct intent and stay
     * separate.
     */
    @Test
    fun `standalone TYPE_IMAGE candidate is never eligible for auto-merge`() {
        val a = anchor(color = "yellow", textSnippet = "Some text", textAfter = "")
        val img = annotation(
            id = "img-1",
            color = "yellow",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        assertEquals(false, isMergeEligible(a, img))
        val html = """
            <html><body>
              <p>Some text</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
            </body></html>
        """.trimIndent()
        val match = findAnyMergeableNeighbor(
            a,
            listOf(img),
            excludeIds = emptySet(),
            html = html,
        )
        assertNull("TYPE_IMAGE candidates are not auto-merged, even with html", match)
    }

}
