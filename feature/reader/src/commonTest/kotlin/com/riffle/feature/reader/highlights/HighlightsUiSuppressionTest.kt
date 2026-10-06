package com.riffle.feature.reader.highlights

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightsUiSuppressionTest {

    @Test
    fun readaloudUiShownForFullBook() {
        assertTrue(shouldShowReadaloudUi(ReaderSource.FullBook))
    }

    @Test
    fun readaloudUiHiddenForHighlights() {
        assertFalse(shouldShowReadaloudUi(ReaderSource.Highlights))
    }

    @Test
    fun openInBookHiddenForFullBook() {
        assertFalse(shouldShowOpenInBook(ReaderSource.FullBook))
    }

    @Test
    fun openInBookShownForHighlights() {
        assertTrue(shouldShowOpenInBook(ReaderSource.Highlights))
    }

    @Test
    fun shareHighlightsHiddenForFullBook() {
        assertFalse(shouldShowShareHighlights(ReaderSource.FullBook))
    }

    @Test
    fun shareHighlightsShownForHighlights() {
        assertTrue(shouldShowShareHighlights(ReaderSource.Highlights))
    }
}
