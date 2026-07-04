package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "tap-to-dismiss text selection popup also toggled immersive mode"
 * bug. The touchstart snapshot in `SELECTION_SPAN_TRACKER_JS` calls
 * `RiffleSelBridge.onActiveAtDown(true)` when the touch starts while a selection is live;
 * `consumeSelectionSuppressedTap` reads that flag inside the paged InputListener.onTap so the
 * immersive-mode toggle is skipped for that tap only.
 *
 * These assertions fail if the flag is not consumed (so a subsequent tap is also swallowed) or
 * if the bridge stops honouring `onActiveAtDown`.
 */
class SelectionSuppressedTapTest {

    @Test
    fun `bridge onActiveAtDown sets the atomic true`() {
        val active = AtomicBoolean(false)
        val bridge = RiffleSelectionRectBridge(AtomicReference(null), active)

        bridge.onActiveAtDown(true)

        assertTrue(active.get())
    }

    @Test
    fun `bridge onActiveAtDown clears the atomic on false`() {
        val active = AtomicBoolean(true)
        val bridge = RiffleSelectionRectBridge(AtomicReference(null), active)

        bridge.onActiveAtDown(false)

        assertFalse(active.get())
    }

    @Test
    fun `consumeSelectionSuppressedTap returns true and clears the flag when set`() {
        val active = AtomicBoolean(true)

        val consumed = consumeSelectionSuppressedTap(active)

        assertTrue("first tap after a live selection must be swallowed", consumed)
        assertFalse("flag must be cleared so the next tap toggles immersive", active.get())
    }

    @Test
    fun `consumeSelectionSuppressedTap returns false when no selection was live at touchstart`() {
        val active = AtomicBoolean(false)

        val consumed = consumeSelectionSuppressedTap(active)

        assertFalse("normal taps must not be swallowed", consumed)
        assertFalse(active.get())
    }

    @Test
    fun `second tap after a suppressed one is not swallowed`() {
        val active = AtomicBoolean(true)

        val first = consumeSelectionSuppressedTap(active)
        val second = consumeSelectionSuppressedTap(active)

        assertTrue(first)
        assertFalse("only the tap that dismissed the selection is swallowed", second)
    }

}
