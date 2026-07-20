package com.riffle.app.feature.reader

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the highlight-picker swatch backdrop to the exact opaque colour Readium paints for each
 * reader theme. Every highlight-colour picker (reader popup, Readaloud settings, Cadence settings)
 * reads [swatchBackdropColor] and paints it under the alpha-0x80 highlight fill so the picker
 * previews the colour against the same paper the book will draw. If someone shifted the picker
 * back to `MaterialTheme.colorScheme.surface`, the picker would follow the app theme instead of
 * the reader theme — the bug this suite exists to prevent.
 *
 * The specific colours mirror Readium's `--RS__backgroundColor` declarations documented in
 * [ReaderThemePalette]. If Readium's palette ever moves, both the palette and Readium's own
 * rendering must move together; this test flips red first.
 */
class ReaderThemePaletteTest {

    @Test
    fun light_theme_backdrop_matches_readium_white_page() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Light)
        assertEquals(Color(0xFFFFFFFF), prefs.swatchBackdropColor)
    }

    @Test
    fun dark_theme_backdrop_matches_readium_black_page() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Dark)
        assertEquals(Color(0xFF000000), prefs.swatchBackdropColor)
    }

    @Test
    fun darkDim_theme_backdrop_reuses_dark_background() {
        val prefs = FormattingPreferences(theme = ReaderTheme.DarkDim)
        assertEquals(Color(0xFF000000), prefs.swatchBackdropColor)
    }

    @Test
    fun sepia_theme_backdrop_matches_readium_sepia_page() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Sepia)
        assertEquals(Color(0xFFFAF4E8), prefs.swatchBackdropColor)
    }

    @Test
    fun auto_theme_backdrop_falls_back_to_light_so_previews_never_crash_on_missed_resolution() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(Color(0xFFFFFFFF), prefs.swatchBackdropColor)
    }
}
