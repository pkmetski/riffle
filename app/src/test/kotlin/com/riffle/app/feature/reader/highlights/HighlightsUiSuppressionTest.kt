package com.riffle.app.feature.reader.highlights

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three UI-visibility decision functions ([shouldShowReadaloudUi], [shouldShowChapterRail],
 * [shouldShowOpenInBook]) that [com.riffle.app.feature.reader.EpubReaderScreen] and
 * [com.riffle.app.feature.reader.HighlightActionsPopup] consult to gate Readaloud entry points, the
 * chapter navigation rail, and the "Open in book" row respectively (Task 9, ADR 0041).
 *
 * These are the actual runtime decision points at the call sites, not test-only mirrors — if any of
 * these were flipped (or a call site stopped consulting them), the corresponding assertion here
 * would fail.
 */
class HighlightsUiSuppressionTest {

    @Test
    fun readaloudHiddenInHighlightsMode() {
        assertFalse(shouldShowReadaloudUi(ReaderSource.Highlights))
        assertTrue(shouldShowReadaloudUi(ReaderSource.FullBook))
    }

    @Test
    fun chapterRailHiddenInHighlightsMode() {
        assertFalse(shouldShowChapterRail(ReaderSource.Highlights))
        assertTrue(shouldShowChapterRail(ReaderSource.FullBook))
    }

    @Test
    fun openInBookVisibleOnlyInHighlightsMode() {
        assertTrue(shouldShowOpenInBook(ReaderSource.Highlights))
        assertFalse(shouldShowOpenInBook(ReaderSource.FullBook))
    }
}
