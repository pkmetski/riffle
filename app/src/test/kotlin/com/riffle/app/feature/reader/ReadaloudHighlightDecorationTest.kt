@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReadaloudHighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.readium.r2.navigator.Decoration

class ReadaloudHighlightDecorationTest {

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    @Test
    fun allColorsHaveAlphaBakedIn() {
        // argb is the final rendered color used by both the swatch and the reader — no runtime
        // transformation applied. Verify every entry has the expected translucent alpha.
        for (color in ReadaloudHighlightColor.entries) {
            assertEquals("alpha for ${color.name}", HIGHLIGHT_ALPHA_LIGHT, alpha(color.argb))
        }
    }

    @Test
    fun fragmentConfigurationRegistersHighlightTemplateAlongsideDefaults() {
        val templates = FormattingPreferences().toFragmentConfiguration().decorationTemplates
        // Our shared tinted-highlight style is registered...
        assertNotNull(templates[HighlightTintStyle::class])
        // ...without dropping the built-in highlight used by persisted + search highlights.
        assertNotNull(templates[Decoration.Style.Highlight::class])
        // ...and the note-glyph style for annotation-notes group is also registered.
        assertNotNull(templates[NoteGlyphStyle::class])
    }
}
