package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for emphasis auto-merge (ADR 0046). Mirrors [HighlightMergeTest]'s shape:
 * eligibility uses `styles` as the match key (same-set-only) instead of `color`, the no-note gate
 * is collapsed (Emphasis carries no note field), and the figure-gap block still applies.
 *
 * Each assertion pins one design decision from ADR 0046 §5. Reverting the corresponding logic in
 * [isMergeEligible] or [MergeAnchor] flips the named test red.
 */
class EmphasisMergeTest {

    private val BOLD = setOf(EmphasisStyle.BOLD)
    private val BOLD_UNDERLINE = setOf(EmphasisStyle.BOLD, EmphasisStyle.UNDERLINE)
    private val ITALIC = setOf(EmphasisStyle.ITALIC)

    private fun emphasis(
        id: String = "e",
        styles: Set<EmphasisStyle> = BOLD,
        textSnippet: String = "",
        textBefore: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
        progression: Double = 0.0,
    ) = Annotation(
        id = id,
        sourceId = "srv",
        itemId = "item",
        type = AnnotationEntity.TYPE_EMPHASIS,
        cfi = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        color = "",
        note = null,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        chapterHref = "ch1.xhtml",
        spineIndex = spineIndex,
        progression = progression,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
        emphasisStyles = styles,
    )

    private fun highlight(
        id: String = "h",
        color: String = "yellow",
        textSnippet: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
    ) = Annotation(
        id = id,
        sourceId = "srv",
        itemId = "item",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        color = color,
        note = null,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = textAfter,
        chapterHref = "ch1.xhtml",
        spineIndex = spineIndex,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun anchor(
        styles: Set<EmphasisStyle>? = BOLD,
        textSnippet: String,
        textBefore: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
        progression: Double = 0.5,
    ) = MergeAnchor(
        spineIndex = spineIndex,
        color = "",
        note = null,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        progression = progression,
        chapterHref = "ch1.xhtml",
        type = AnnotationEntity.TYPE_EMPHASIS,
        emphasisStyles = styles,
    )

    // -------- Eligibility (pins ADR 0046 §5: same-styles-set match, distinct sets never merge) --

    @Test
    fun `same styles set in same chapter is eligible`() {
        val a = anchor(styles = BOLD, textSnippet = "Foo", textAfter = " Bar")
        val n = emphasis(id = "b", styles = BOLD, textSnippet = "Bar")
        assertTrue(isMergeEligible(a, n))
    }

    @Test
    fun `different styles sets are not eligible`() {
        val a = anchor(styles = BOLD, textSnippet = "Foo", textAfter = " Bar")
        val n = emphasis(styles = ITALIC, textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `overlapping styles sets that are not equal are not eligible`() {
        // ADR 0046: only IDENTICAL sets merge; {bold} and {bold, underline} don't collapse.
        val a = anchor(styles = BOLD, textSnippet = "Foo", textAfter = " Bar")
        val n = emphasis(styles = BOLD_UNDERLINE, textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `different chapters (spineIndex) are not eligible`() {
        val a = anchor(styles = BOLD, textSnippet = "Foo", textAfter = " Bar", spineIndex = 0)
        val n = emphasis(styles = BOLD, textSnippet = "Bar", spineIndex = 1)
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `emphasis anchor never merges with a highlight neighbour`() {
        // The type-first check protects cross-type merging even if all other fields matched.
        val a = anchor(styles = BOLD, textSnippet = "Foo", textAfter = " Bar")
        val n = highlight(color = "yellow", textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    @Test
    fun `empty anchor styles set is never eligible (defensive)`() {
        // An empty-styles emphasis row is not a legal state — createEmphasis rejects it — but
        // defence-in-depth: even if one leaked in via a decode error, it must never absorb a peer.
        val a = anchor(styles = emptySet(), textSnippet = "Foo", textAfter = " Bar")
        val n = emphasis(styles = BOLD, textSnippet = "Bar")
        assertEquals(false, isMergeEligible(a, n))
    }

    // -------- Adjacency (reuses HighlightMerge's text-context walk; test the emphasis path) -----

    @Test
    fun `same-styles adjacency after anchor produces a MergeCandidate`() {
        val a = anchor(styles = BOLD, textSnippet = "the door", textAfter = " was never locked")
        val n = emphasis(styles = BOLD, textSnippet = "was never locked", textBefore = "the door ")
        val match = findAnyMergeableNeighbor(a, listOf(n), emptySet())
        assertNotNull(match)
        assertEquals(MergeSide.CANDIDATE_AFTER_ANCHOR, match!!.side)
    }

    @Test
    fun `distinct-styles adjacency does not merge — kept as two rows, layered at render`() {
        val a = anchor(styles = BOLD, textSnippet = "the door", textAfter = " was never locked")
        val n = emphasis(styles = ITALIC, textSnippet = "was never locked", textBefore = "the door ")
        assertNull(findAnyMergeableNeighbor(a, listOf(n), emptySet()))
    }

    // -------- Merge action (pins ADR 0046: snippets combine with whitespace, styles inherited) --

    @Test
    fun `applyMerge unions text spans and keeps the anchor's styles`() {
        val a = anchor(styles = BOLD_UNDERLINE, textSnippet = "Foo", textAfter = " Bar",
            textBefore = "Before ", progression = 0.5)
        val n = emphasis(styles = BOLD_UNDERLINE, textSnippet = "Bar", textBefore = "Before Foo ",
            textAfter = "and continues", progression = 0.6)
        val match = MergeCandidate(n, MergeSide.CANDIDATE_AFTER_ANCHOR, " ")
        val merged = applyMerge(a, match)
        assertEquals("Foo Bar", merged.textSnippet)
        assertEquals("Before ", merged.textBefore)
        assertEquals("and continues", merged.textAfter)
        // Styles are on the anchor, not carried on MergeCandidate — the produced anchor keeps them.
        assertEquals(BOLD_UNDERLINE, merged.emphasisStyles)
        assertEquals(AnnotationEntity.TYPE_EMPHASIS, merged.type)
    }

    // -------- Chain merge (pins loop-until-stable) --------

    @Test
    fun `chain merge absorbs both neighbours across two passes`() {
        var current = anchor(styles = BOLD, textSnippet = "second", textBefore = "first ",
            textAfter = " third")
        val left = emphasis(id = "L", styles = BOLD, textSnippet = "first",
            textAfter = " second third")
        val right = emphasis(id = "R", styles = BOLD, textSnippet = "third",
            textBefore = "first second ")
        val excluded = mutableSetOf<String>()

        val first = findAnyMergeableNeighbor(current, listOf(left, right), excluded)
        assertNotNull(first)
        current = applyMerge(current, first!!)
        excluded += first.neighbor.id

        val second = findAnyMergeableNeighbor(current, listOf(left, right), excluded)
        assertNotNull(second)
        current = applyMerge(current, second!!)

        assertEquals("first second third", current.textSnippet)
        assertEquals(BOLD, current.emphasisStyles)
    }
}
