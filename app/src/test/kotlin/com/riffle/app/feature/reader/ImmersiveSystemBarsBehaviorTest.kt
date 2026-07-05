package com.riffle.app.feature.reader

import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the Android 7.1.1 (API 25) transparent-bar bug: pre-R devices
 * must use sticky-immersive so the OS re-hides bars after a transient reveal.
 * Reverting this to [BEHAVIOR_DEFAULT] on pre-R leaves the nav/status bars showing with
 * transparent backgrounds after any tap, because `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`
 * remains set and the topInset watcher can't detect the reveal.
 */
class ImmersiveSystemBarsBehaviorTest {

    @Test
    fun `pre-R uses sticky transient bars so system auto-re-hides after reveal`() {
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.N_MR1),
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `R and later use BEHAVIOR_DEFAULT so side-edge swipes do not flash chrome`() {
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.R),
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }
}
