package com.riffle.app.feature.reader.highlights

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the UI-visibility decision functions ([shouldShowReadaloudUi], [shouldShowOpenInBook])
 * that [com.riffle.app.feature.reader.EpubReaderScreen] and
 * [com.riffle.app.feature.reader.HighlightActionsPopup] consult to gate Readaloud entry points and
 * the "Open in book" row respectively (Task 9, ADR 0041).
 *
 * The chapter navigation rail is intentionally NOT gated on [ReaderSource] anymore — the elided
 * (Highlights-mode) publication has a flat one-entry-per-chapter TOC, so the rail's segment
 * builder produces a meaningful "one segment per elided chapter" layout without needing subchapter
 * resolution against the real book. If a future change re-introduces a rail suppression, add a
 * dedicated test for it rather than expanding this one.
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
    fun openInBookVisibleOnlyInHighlightsMode() {
        assertTrue(shouldShowOpenInBook(ReaderSource.Highlights))
        assertFalse(shouldShowOpenInBook(ReaderSource.FullBook))
    }
}
