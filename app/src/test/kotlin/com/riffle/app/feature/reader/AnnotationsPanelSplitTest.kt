package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression pins for `splitSnippetForFigures` — the heuristic that interleaves a highlight's
 * `textSnippet` with its embedded figures for the annotations panel's inline-figure rendering
 * (fix #2, 2026-07-09). Would flip red if the split rule or the fallback chunk count changed.
 */
class AnnotationsPanelSplitTest {

    @Test
    fun `snippet with one newline splits into two chunks around a single figure`() {
        val out = splitSnippetForFigures("first paragraph\nsecond paragraph", figureCount = 1)
        assertEquals(listOf("first paragraph", "second paragraph"), out)
    }

    @Test
    fun `no newline snippet falls back to text then figure`() {
        val out = splitSnippetForFigures("just one paragraph", figureCount = 1)
        assertEquals(listOf("just one paragraph", ""), out)
    }

    @Test
    fun `no newline snippet with zero figures returns snippet only`() {
        val out = splitSnippetForFigures("just one paragraph", figureCount = 0)
        assertEquals(listOf("just one paragraph"), out)
    }

    @Test
    fun `two figures with two paragraph breaks interleave at both gaps`() {
        val out = splitSnippetForFigures("a\nb\nc", figureCount = 2)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `more paragraphs than figures collapse the tail into the last chunk`() {
        val out = splitSnippetForFigures("a\nb\nc\nd", figureCount = 1)
        assertEquals(listOf("a", "b\nc\nd"), out)
    }

    @Test
    fun `fewer paragraphs than needed pads with empty chunks`() {
        val out = splitSnippetForFigures("only one", figureCount = 2)
        assertEquals(listOf("only one", "", ""), out)
    }

    @Test
    fun `blank-lined paragraphs are filtered before split`() {
        // `\n\n` produces a single blank between two chunks; the blank is dropped, leaving 2 chunks
        // for 1 figure — the ideal case.
        val out = splitSnippetForFigures("before\n\nafter", figureCount = 1)
        assertEquals(listOf("before", "after"), out)
    }

    // -------- splitSnippetForFiguresAt (fix 2026-07-09) --------

    /** One figure at a known offset splits the snippet at that exact char boundary. */
    @Test
    fun `splitAt splits snippet at figure's char offset`() {
        val snippet = "text-before-figuretext-after-figure"
        val out = splitSnippetForFiguresAt(snippet, listOf(18L))
        assertEquals(listOf("text-before-figure", "text-after-figure"), out)
    }

    /** Figure at offset 0 produces an empty leading chunk — figure renders first, then all text. */
    @Test
    fun `splitAt with offset zero yields empty leading chunk`() {
        val out = splitSnippetForFiguresAt("everything after", listOf(0L))
        assertEquals(listOf("", "everything after"), out)
    }

    /** Offset beyond snippet length clamps to the end — figure renders after all text. */
    @Test
    fun `splitAt with offset beyond snippet clamps to end`() {
        val out = splitSnippetForFiguresAt("short", listOf(9999L))
        assertEquals(listOf("short", ""), out)
    }

    /** Two figures at two offsets produce three chunks (before, between, after). */
    @Test
    fun `splitAt with two ordered offsets produces three chunks`() {
        val snippet = "AAABBBCCC"
        val out = splitSnippetForFiguresAt(snippet, listOf(3L, 6L))
        assertEquals(listOf("AAA", "BBB", "CCC"), out)
    }

    /**
     * Any null offset in the list falls back to the paragraph-heuristic — pins that a legacy row
     * (some figures without captured offsets) still renders sanely without mixing modes.
     */
    @Test
    fun `splitAt falls back to heuristic when all offsets are null`() {
        val out = splitSnippetForFiguresAt("first\nsecond", listOf<Long?>(null))
        assertEquals(listOf("first", "second"), out)
    }
}
