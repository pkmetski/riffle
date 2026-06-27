package com.riffle.app.ui

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarScrimTest {
    // Guards the invariant behind MainActivity pinning `isAppearanceLightNavigationBars = false`:
    // the nav-bar inset is always painted by BottomNavBarScrim, so if the scrim ever stops being
    // dark, the system nav-button tint decision in MainActivity needs to be revisited. Without
    // that pin, light-mode renders dark glyphs on this dark scrim and the gesture pill / 3-button
    // nav vanishes on API 26+ (observed on Android 13).
    @Test
    fun navBarScrimRendersDarkSoSystemGlyphsMustStayLight() {
        val scrim = bottomBarScrimColor()
        // Compose's perceptual luminance: 0 = black, 1 = white. Anything under ~0.5 is "dark".
        // The scrim is Color.Black at 0.6 alpha; alpha doesn't affect luminance() but the RGB
        // channels do — this fails the moment someone swaps the base color for a light tone.
        assertTrue(
            "BottomNavBarScrim must stay dark or MainActivity.isAppearanceLightNavigationBars needs re-evaluating",
            scrim.luminance() < 0.5f,
        )
    }
}
