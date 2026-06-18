@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.readium.r2.navigator.Decoration

class ReadaloudHighlightDecorationTest {

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    private fun rgb(argb: Int): Int = argb and 0x00FFFFFF

    @Test
    fun darkThemesUseStrongerAlpha() {
        for (theme in listOf(ReaderTheme.Dark, ReaderTheme.DarkDim)) {
            val tint = ReadaloudHighlightColor.BLUE.readerTint(theme)
            assertEquals("alpha for $theme", HIGHLIGHT_ALPHA_DARK, alpha(tint))
            // The hue itself is preserved; only the alpha byte changes.
            assertEquals(rgb(ReadaloudHighlightColor.BLUE.argb), rgb(tint))
        }
    }

    @Test
    fun lightThemesUseDefaultAlpha() {
        for (theme in listOf(ReaderTheme.Light, ReaderTheme.Sepia, ReaderTheme.Auto)) {
            val tint = ReadaloudHighlightColor.GREEN.readerTint(theme)
            assertEquals("alpha for $theme", HIGHLIGHT_ALPHA_LIGHT, alpha(tint))
            assertEquals(rgb(ReadaloudHighlightColor.GREEN.argb), rgb(tint))
        }
    }

    @Test
    fun fragmentConfigurationRegistersHighlightTemplateAlongsideDefaults() {
        val templates = FormattingPreferences().toFragmentConfiguration().decorationTemplates
        // Our shared tinted-highlight style is registered...
        assertNotNull(templates[HighlightTintStyle::class])
        // ...without dropping the built-in highlight used by persisted + search highlights.
        assertNotNull(templates[Decoration.Style.Highlight::class])
    }
}
