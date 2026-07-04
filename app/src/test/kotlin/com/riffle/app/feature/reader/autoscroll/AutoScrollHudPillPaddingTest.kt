package com.riffle.app.feature.reader.autoscroll

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the auto-scroll HUD pill bottom padding. The pill sits at BottomEnd of the
 * reader; a padding of 12dp lets it overlap the chapter-rail / reading-status overlay. Guard the
 * lower bound so a well-meaning cleanup that inlines `12.dp` again fails here rather than shipping.
 */
class AutoScrollHudPillPaddingTest {
    @Test
    fun `HUD pill bottom padding clears the reading-status rail`() {
        assertTrue(
            "HUD_PILL_BOTTOM_DP ($HUD_PILL_BOTTOM_DP) must be >= HUD_PILL_MIN_BOTTOM_DP ($HUD_PILL_MIN_BOTTOM_DP) so the pill does not overlap the reader's bottom rail",
            HUD_PILL_BOTTOM_DP >= HUD_PILL_MIN_BOTTOM_DP,
        )
    }
}
