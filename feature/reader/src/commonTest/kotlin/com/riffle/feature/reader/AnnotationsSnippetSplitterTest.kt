package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationsSnippetSplitterTest {

    @Test
    fun snippetWithOneNewlineSplitsIntoTwoChunksAroundASingleFigure() {
        val out = splitSnippetForFigures("first paragraph\nsecond paragraph", figureCount = 1)
        assertEquals(listOf("first paragraph", "second paragraph"), out)
    }

    @Test
    fun noNewlineSnippetFallsBackToTextThenFigure() {
        val out = splitSnippetForFigures("just one paragraph", figureCount = 1)
        assertEquals(listOf("just one paragraph", ""), out)
    }

    @Test
    fun noNewlineSnippetWithZeroFiguresReturnsSnippetOnly() {
        val out = splitSnippetForFigures("just one paragraph", figureCount = 0)
        assertEquals(listOf("just one paragraph"), out)
    }

    @Test
    fun twoFiguresWithTwoParagraphBreaksInterleaveAtBothGaps() {
        val out = splitSnippetForFigures("a\nb\nc", figureCount = 2)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun moreParagraphsThanFiguresCollapseTheTailIntoTheLastChunk() {
        val out = splitSnippetForFigures("a\nb\nc\nd", figureCount = 1)
        assertEquals(listOf("a", "b\nc\nd"), out)
    }

    @Test
    fun fewerParagraphsThanNeededPadsWithEmptyChunks() {
        val out = splitSnippetForFigures("only one", figureCount = 2)
        assertEquals(listOf("only one", "", ""), out)
    }

    @Test
    fun blankLinedParagraphsAreFilteredBeforeSplit() {
        val out = splitSnippetForFigures("before\n\nafter", figureCount = 1)
        assertEquals(listOf("before", "after"), out)
    }

    @Test
    fun splitAtSplitsSnippetAtFigureCharOffset() {
        val snippet = "text-before-figuretext-after-figure"
        val out = splitSnippetForFiguresAt(snippet, listOf(18L))
        assertEquals(listOf("text-before-figure", "text-after-figure"), out)
    }

    @Test
    fun splitAtWithOffsetZeroYieldsEmptyLeadingChunk() {
        val out = splitSnippetForFiguresAt("everything after", listOf(0L))
        assertEquals(listOf("", "everything after"), out)
    }

    @Test
    fun splitAtWithOffsetBeyondSnippetClampsToEnd() {
        val out = splitSnippetForFiguresAt("short", listOf(9999L))
        assertEquals(listOf("short", ""), out)
    }

    @Test
    fun splitAtWithTwoOrderedOffsetsProducesThreeChunks() {
        val snippet = "AAABBBCCC"
        val out = splitSnippetForFiguresAt(snippet, listOf(3L, 6L))
        assertEquals(listOf("AAA", "BBB", "CCC"), out)
    }

    @Test
    fun splitAtFallsBackToHeuristicWhenAllOffsetsAreNull() {
        val out = splitSnippetForFiguresAt("first\nsecond", listOf<Long?>(null))
        assertEquals(listOf("first", "second"), out)
    }
}
