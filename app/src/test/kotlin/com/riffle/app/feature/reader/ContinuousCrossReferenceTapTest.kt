package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "tapping a same-document cross-reference whose target is already visible must be a
 * no-op" rule for Continuous mode. Without the fix, [handleContinuousCrossReferenceTap] would
 * always invoke `onCaptureAndScroll`, dropping a return-to-position card and recentring the
 * page on an anchor the user is already looking at.
 */
class ContinuousCrossReferenceTapTest {

    @Test
    fun `anchor already visible — onCaptureAndScroll is NOT invoked`() {
        var invoked = 0
        handleContinuousCrossReferenceTap(
            absoluteY = 1500,
            currentScrollY = 1000,
            viewportHeight = 800,
            onCaptureAndScroll = { invoked++ },
        )
        assertEquals("in-viewport tap must be a no-op", 0, invoked)
    }

    @Test
    fun `anchor above the viewport — onCaptureAndScroll IS invoked`() {
        var invoked = 0
        handleContinuousCrossReferenceTap(
            absoluteY = 200,
            currentScrollY = 1000,
            viewportHeight = 800,
            onCaptureAndScroll = { invoked++ },
        )
        assertEquals("off-screen (above) tap must scroll + capture return", 1, invoked)
    }

    @Test
    fun `anchor below the viewport — onCaptureAndScroll IS invoked`() {
        var invoked = 0
        handleContinuousCrossReferenceTap(
            absoluteY = 3000,
            currentScrollY = 1000,
            viewportHeight = 800,
            onCaptureAndScroll = { invoked++ },
        )
        assertEquals("off-screen (below) tap must scroll + capture return", 1, invoked)
    }

    @Test
    fun `anchor at the top edge of the viewport — no-op`() {
        var invoked = 0
        handleContinuousCrossReferenceTap(
            absoluteY = 1000,
            currentScrollY = 1000,
            viewportHeight = 800,
            onCaptureAndScroll = { invoked++ },
        )
        assertTrue("top edge counts as visible", invoked == 0)
    }

    @Test
    fun `unresolved anchor (null absoluteY) — falls through to scroll so the tap is not silently dropped`() {
        var invoked = 0
        handleContinuousCrossReferenceTap(
            absoluteY = null,
            currentScrollY = 1000,
            viewportHeight = 800,
            onCaptureAndScroll = { invoked++ },
        )
        assertFalse("null anchor must not be treated as visible", invoked == 0)
    }
}
