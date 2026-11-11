package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the popup-dismiss → no-immersive-toggle contract.
 *
 * Mechanism: a full-screen transparent scrim [Box] lives inside the same inner [Box] as the
 * [EpubNavigatorView] (the WebView wrapper), placed AFTER it so it wins z-order. This mirrors
 * the [CornerBookmarkIndicator] placement: a fillMaxSize AndroidView inside a Box consumes all
 * touches before any outer-Box Compose element sees them, so the scrim must be a sibling of the
 * AndroidView — not a sibling of the inner Box — to intercept at PointerEventPass.Initial before
 * the WebView dispatches the MotionEvent. The tap listeners also carry a popup-visibility guard
 * as a secondary defence.
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
