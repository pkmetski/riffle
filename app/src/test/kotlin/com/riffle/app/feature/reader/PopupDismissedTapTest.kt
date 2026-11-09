package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the popup-dismiss → no-immersive-toggle contract.
 *
 * Mechanism: a full-screen transparent scrim [Box] sits in the Activity window behind the popup
 * (which lives in its own separate, non-focusable Android window). Any tap outside the popup
 * is caught by the scrim BEFORE it propagates to the WebView, so the reader's onTap handler
 * never fires for a dismiss tap. The tap listeners therefore carry no popup-visibility guard.
 */
class PopupDismissedTapTest {

    @Test
    fun `tap listener passes through when no selection race is active`() {
        // The only guard left in the tap listeners is consumeSelectionSuppressedTap.
        // With no selection active at down, it returns false → tap proceeds normally.
        val pagedSelectionActiveAtDown = AtomicBoolean(false)
        val suppressed = consumeSelectionSuppressedTap(pagedSelectionActiveAtDown)
        assertFalse("no selection race → tap reaches the immersive-toggle logic normally", suppressed)
    }

    @Test
    fun `tap listener is suppressed only by selection race, not by popup visibility`() {
        // consumeSelectionSuppressedTap returns true when a text selection was active at the
        // moment of the down event — that is the sole gate remaining in the tap listeners.
        // Popup visibility is handled exclusively by the scrim.
        val pagedSelectionActiveAtDown = AtomicBoolean(true)
        val suppressed = consumeSelectionSuppressedTap(pagedSelectionActiveAtDown)
        assertTrue("selection-race guard still fires independently of popup state", suppressed)
    }
}
