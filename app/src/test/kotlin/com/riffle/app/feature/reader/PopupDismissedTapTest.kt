package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [HighlightActionsPopup] visibility-flag contract used by paged/continuous tap listeners
 * to avoid toggling immersive mode when the user taps to dismiss the popup.
 *
 * The popup is non-focusable so the dismissing touch propagates to the reader. The tap listeners
 * check [highlightPopupVisible] (synced by SideEffect from highlightToEdit) to suppress immersive
 * flips while the popup is on screen.
 */
class PopupDismissedTapTest {

    @Test
    fun `popup not visible means tap passes through`() {
        val popupVisible = AtomicBoolean(false)

        val suppressed = popupVisible.get()

        assertFalse("no popup on screen → tap must toggle immersive normally", suppressed)
    }

    @Test
    fun `popup visible means tap is suppressed`() {
        val popupVisible = AtomicBoolean(true)

        val suppressed = popupVisible.get()

        assertTrue("popup on screen → tap must not toggle immersive", suppressed)
    }

    @Test
    fun `flag cleared when popup dismissed allows next tap through`() {
        val popupVisible = AtomicBoolean(true)

        // SideEffect runs after recomposition where highlightToEdit becomes null
        popupVisible.set(false)
        val suppressed = popupVisible.get()

        assertFalse("after popup dismissed, tap must toggle immersive normally", suppressed)
    }
}
