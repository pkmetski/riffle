package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.highlights.ReaderSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single decision function ([shouldRunReadingSideEffects]) that
 * [EpubReaderViewModel] consults at every Reading-Session / progress-sync suppression site
 * (startReadingSession, syncCurrentPosition, the serverPositionEvents collector, onReaderClosed)
 * plus the eager `source == ReaderSource.Highlights` guards on createHighlight/toggleBookmark
 * (Task 8, ADR 0041).
 *
 * [EpubReaderViewModel] cannot be constructed in a JVM test (Robolectric-only constraint
 * documented in Task 7); this test therefore exercises the extracted pure decision function
 * directly, following the same pattern as [HighlightOverlapTest] testing
 * `highlightOverlapsAtSamePosition`.
 */
class EpubReaderViewModelHighlightsSuppressionTest {

    @Test
    fun highlightsMode_suppressesReadingSideEffects() {
        assertFalse(shouldRunReadingSideEffects(ReaderSource.Highlights))
    }

    @Test
    fun fullBookMode_runsReadingSideEffects() {
        assertTrue(shouldRunReadingSideEffects(ReaderSource.FullBook))
    }
}
