package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for "tap that dismissed the HighlightActionsPopup also toggled immersive
 * mode". [HighlightActionsPopup] is deliberately non-focusable so the popup Window doesn't steal
 * input focus (any focus transfer causes the OS to reveal the system bars), but that means the
 * outside tap that dismisses the popup also propagates to the reader's tap listener. Recording
 * the dismiss timestamp and consuming the very next tap keeps immersive mode intact.
 *
 * These assertions fail if the timestamp is not consumed (so a later tap is also swallowed) or
 * if the suppression window stops honouring the dismiss time.
 */
class PopupDismissedTapTest {

    @Test
    fun `no dismiss recorded means tap passes through`() {
        val at = AtomicLong(0L)

        val consumed = consumePopupDismissedTap(at, nowNs = 1_000_000_000L)

        assertFalse("no popup dismissal → tap must toggle immersive normally", consumed)
    }

    @Test
    fun `tap within window after dismiss is swallowed and timestamp cleared`() {
        val at = AtomicLong(500_000_000L)

        val consumed = consumePopupDismissedTap(
            dismissedAtNs = at,
            nowNs = 500_000_000L + 10_000_000L, // 10ms after dismiss
        )

        assertTrue("first tap after popup dismissal must be swallowed", consumed)
        assertEquals(
            "timestamp must be cleared so the next tap is not also swallowed",
            0L,
            at.get(),
        )
    }

    @Test
    fun `tap outside window is not swallowed`() {
        val at = AtomicLong(500_000_000L)
        val windowMs = 300L
        val nowNs = 500_000_000L + (windowMs + 50) * 1_000_000L

        val consumed = consumePopupDismissedTap(at, nowNs = nowNs, windowMs = windowMs)

        assertFalse("tap after the window closes must toggle immersive normally", consumed)
    }

    @Test
    fun `second tap after a suppressed one is not swallowed`() {
        val at = AtomicLong(500_000_000L)

        val first = consumePopupDismissedTap(at, nowNs = 501_000_000L)
        val second = consumePopupDismissedTap(at, nowNs = 502_000_000L)

        assertTrue(first)
        assertFalse("only the tap that dismissed the popup is swallowed", second)
    }

}
