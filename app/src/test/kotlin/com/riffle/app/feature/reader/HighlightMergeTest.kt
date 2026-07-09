package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
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

    // -------- Cross-figure merge (symmetric with text): pin the current behaviour --------

    /**
     * Two same-colour no-note text highlights on either side of a figure MUST be mergeable — the
     * figure between them counts as zero readable chars in Readium's textBefore/textAfter and the
     * user's mental model treats text-across-figure the same as text-across-nothing (memory
     * `annotations-text-graph-symmetric`). This test pins that pre-existing text adjacency wins.
     * The old figureGuard reversed this and shipped the wrong fix; this assertion would flip red
     * if a "reject cross-figure text merge" gate were reintroduced.
     */
    @Test
    fun `same-colour text highlights on either side of a figure merge as ordinary neighbours`() {
        val anchor = anchor(
            textSnippet = "The overall complexity of a system (C) is determined by the complexity",
            textBefore = "characterize this in a crude mathematical way:",
            textAfter = " of each part.",
        )
        val neighbor = annotation(
            id = "before-fig",
            textSnippet = "To characterize this in a crude mathematical way:",
            textAfter = "The overall complexity of a system",
        )
        val match = findAnyMergeableNeighbor(anchor, listOf(neighbor), excludeIds = emptySet())
        assertNotNull("cross-figure text adjacency must merge — figure is invisible to the text stream", match)
        assertEquals(MergeSide.CANDIDATE_BEFORE_ANCHOR, match!!.side)
    }

    // -------- Figure absorption (2026-07-09) --------

    /**
     * Text highlight covering a figure absorbs a redundant standalone TYPE_IMAGE annotation at
     * that figure. Would flip red if [findAbsorbableImageAnnotations] stops matching enclosed
     * figures by filename — the exact regression from removing the fix.
     */
    @Test
    fun `text highlight enclosing a figure absorbs the standalone image annotation on it`() {
        val html = """
            <html><body>
              <p>Before figure text goes here.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>After figure text extends beyond.</p>
            </body></html>
        """.trimIndent()
        val standaloneImage = annotation(
            id = "img-1",
            color = "yellow",
            textSnippet = "the figure caption",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        val pool = listOf(standaloneImage)
        val absorbable = findAbsorbableImageAnnotations(
            html = html,
            snippet = "Before figure text goes here.After figure text extends beyond.",
            textBefore = "",
            color = "yellow",
            chapterHref = "ch1.xhtml",
            pool = pool,
            excludeIds = emptySet(),
        )
        assertEquals(listOf("img-1"), absorbable.map { it.id })
    }

    /**
     * Figure sitting at the range endpoint (touching but not covered) is still absorbable — the
     * widening by 1 char catches boundary figures. Removing the widening would flip this red.
     */
    @Test
    fun `image annotation at range boundary is absorbable via widened check`() {
        val html = """
            <html><body>
              <p>Only text here.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>More text.</p>
            </body></html>
        """.trimIndent()
        val standaloneImage = annotation(
            id = "img-1",
            color = "yellow",
            textSnippet = "",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        // Highlight snippet is "Only text here." — its end coincides with the figure position.
        val absorbable = findAbsorbableImageAnnotations(
            html = html,
            snippet = "Only text here.",
            textBefore = "",
            color = "yellow",
            chapterHref = "ch1.xhtml",
            pool = listOf(standaloneImage),
            excludeIds = emptySet(),
        )
        assertEquals(listOf("img-1"), absorbable.map { it.id })
    }

    /** Different colours are NOT absorbable — the same-colour eligibility rule applies. */
    @Test
    fun `image annotation with mismatching colour is not absorbable`() {
        val html = """
            <html><body>
              <p>Text before.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>Text after.</p>
            </body></html>
        """.trimIndent()
        val bluePool = listOf(
            annotation(
                id = "img-1",
                color = "blue",
                textSnippet = "",
                type = AnnotationEntity.TYPE_IMAGE,
            ).copy(imageHref = "OEBPS/g.png"),
        )
        val absorbable = findAbsorbableImageAnnotations(
            html = html,
            snippet = "Text before.Text after.",
            textBefore = "",
            color = "yellow",
            chapterHref = "ch1.xhtml",
            pool = bluePool,
            excludeIds = emptySet(),
        )
        assertTrue(absorbable.isEmpty())
    }

    /** Image annotation with a note is NOT absorbable — user has meaningful data on it. */
    @Test
    fun `image annotation with a note is not absorbable`() {
        val html = """
            <html><body>
              <p>Text before.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>Text after.</p>
            </body></html>
        """.trimIndent()
        val notedPool = listOf(
            annotation(
                id = "img-1",
                color = "yellow",
                note = "Important observation about this graph",
                textSnippet = "",
                type = AnnotationEntity.TYPE_IMAGE,
            ).copy(imageHref = "OEBPS/g.png"),
        )
        val absorbable = findAbsorbableImageAnnotations(
            html = html,
            snippet = "Text before.Text after.",
            textBefore = "",
            color = "yellow",
            chapterHref = "ch1.xhtml",
            pool = notedPool,
            excludeIds = emptySet(),
        )
        assertTrue(absorbable.isEmpty())
    }

    // -------- Figure candidates (findFigureAdjacency + findAnyMergeableNeighbor with html) --------

    /**
     * A text highlight touching a figure absorbs the standalone TYPE_IMAGE candidate as a
     * mergeable neighbour via [findFigureAdjacency]. Symmetric with text-neighbour merging: the
     * candidate is returned by [findAnyMergeableNeighbor] when it's given the chapter HTML.
     */
    @Test
    fun `TYPE_IMAGE candidate at highlight range boundary is returned as a mergeable neighbour`() {
        val html = """
            <html><body>
              <p>The paragraph text before the figure.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>The paragraph after the figure.</p>
            </body></html>
        """.trimIndent()
        val anchor = anchor(
            color = "yellow",
            textSnippet = "The paragraph text before the figure.",
            textBefore = "",
        )
        val figureCandidate = annotation(
            id = "img-1",
            color = "yellow",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        val match = findAnyMergeableNeighbor(
            anchor,
            listOf(figureCandidate),
            excludeIds = emptySet(),
            html = html,
        )
        assertNotNull("figure at range boundary must be mergeable when html is provided", match)
        assertEquals("img-1", match!!.neighbor.id)
    }

    /** No chapter HTML → TYPE_IMAGE candidates are silently skipped, text-only pool works. */
    @Test
    fun `findAnyMergeableNeighbor without html skips TYPE_IMAGE candidates`() {
        val anchor = anchor(color = "yellow", textSnippet = "Foo", textAfter = " Bar")
        val figureCandidate = annotation(
            id = "img-1",
            color = "yellow",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        val match = findAnyMergeableNeighbor(anchor, listOf(figureCandidate), excludeIds = emptySet())
        assertNull("no html → image candidate skipped", match)
    }

    /**
     * A TYPE_IMAGE candidate whose figure sits neither inside nor touching the anchor's range is
     * NOT adjacent. Would flip red if the widening in [findFigureAdjacency] were unbounded.
     */
    @Test
    fun `TYPE_IMAGE candidate whose figure is far from the anchor's range is not adjacent`() {
        val html = """
            <html><body>
              <p>Paragraph one with the highlight.</p>
              <p>Paragraph two, unrelated.</p>
              <div><img src="OEBPS/g.png" alt=""/></div>
              <p>Paragraph three.</p>
            </body></html>
        """.trimIndent()
        val anchor = anchor(
            color = "yellow",
            textSnippet = "Paragraph one",
            textBefore = "",
        )
        val figureCandidate = annotation(
            id = "img-1",
            color = "yellow",
            type = AnnotationEntity.TYPE_IMAGE,
        ).copy(imageHref = "OEBPS/g.png")
        val match = findAnyMergeableNeighbor(
            anchor,
            listOf(figureCandidate),
            excludeIds = emptySet(),
            html = html,
        )
        assertNull("figure far from anchor must NOT be adjacent", match)
    }
}
