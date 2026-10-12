package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for AnnotationsFigureUtils.splitSnippetForFiguresAt.
 *
 * The function was previously defined inline in AnnotationsPanel.kt (deleted in favour of
 * SharedAnnotationsPanel). It now lives in AnnotationsFigureUtils.kt and delegates to the
 * shared implementation. This test would fail to compile if the file is removed, and would
 * assert-fail if the delegation drifts from the shared function's behaviour.
 */
class AnnotationsFigureUtilsTest {

    @Test
    fun noFigureOffsets_returnsSingleSegment() {
        val result = splitSnippetForFiguresAt("hello world", emptyList())
        assertEquals(listOf("hello world"), result)
    }

    @Test
    fun singleFigureOffset_splitsAtOffset() {
        val result = splitSnippetForFiguresAt("abcdef", listOf(3L))
        assertEquals(listOf("abc", "def"), result)
    }

    @Test
    fun allNullOffsets_delegatesToParagraphSplit() {
        // All-null offsets fall through to splitSnippetForFigures(snippet, count),
        // which produces (figureCount + 1) segments from newline-separated paragraphs.
        val result = splitSnippetForFiguresAt("abcdef", listOf(null))
        // No newlines → one non-blank part + one trailing empty = ["abcdef", ""]
        assertEquals(listOf("abcdef", ""), result)
    }

    @Test
    fun offsetAtStart_producesEmptyLeadingSegment() {
        val result = splitSnippetForFiguresAt("abcdef", listOf(0L))
        assertEquals(listOf("", "abcdef"), result)
    }

    @Test
    fun offsetAtEnd_producesEmptyTrailingSegment() {
        val result = splitSnippetForFiguresAt("abcdef", listOf(6L))
        assertEquals(listOf("abcdef", ""), result)
    }

    @Test
    fun multipleOffsets_splitsAllAtOnce() {
        val result = splitSnippetForFiguresAt("abcdef", listOf(2L, 4L))
        assertEquals(listOf("ab", "cd", "ef"), result)
    }
}
