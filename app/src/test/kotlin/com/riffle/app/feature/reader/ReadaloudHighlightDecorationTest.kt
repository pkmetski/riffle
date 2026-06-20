@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReadaloudHighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.Decoration

class ReadaloudHighlightDecorationTest {

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    // ---- toCssRgbaWithAlpha --------------------------------------------------

    @Test
    fun `toCssRgbaWithAlpha extracts RGB from ARGB and uses provided alpha`() {
        // 0xFFF5A623 = alpha=FF r=F5 g=A6 b=23 (SEARCH_ACTIVE_ARGB)
        val color = 0xFFF5A623.toInt()
        assertEquals("rgba(245,166,35,0.30)", color.toCssRgbaWithAlpha(0.30))
    }

    @Test
    fun `toCssRgbaWithAlpha ignores the alpha channel of the ARGB int`() {
        // Same RGB as above but with a different alpha in the ARGB value — output alpha must be the argument.
        val fullyOpaque = 0xFFF5A623.toInt()
        val semiTransparent = 0x80F5A623.toInt()
        assertEquals(fullyOpaque.toCssRgbaWithAlpha(0.50), semiTransparent.toCssRgbaWithAlpha(0.50))
    }

    @Test
    fun `toCssRgbaWithAlpha formats alpha to two decimal places`() {
        val result = 0xFFFF0000.toInt().toCssRgbaWithAlpha(0.30)
        assertTrue("expected '0.30' in '$result'", result.contains("0.30"))
    }

    @Test
    fun `SEARCH_DECORATION_ALPHA matches Readium implicit highlight alpha`() {
        // Readium's Decoration.Style.Highlight always renders at 0.30 opacity.
        // Pin the constant so it never silently drifts.
        assertEquals(0.30, SEARCH_DECORATION_ALPHA, 0.001)
    }

    @Test
    fun allColorsHaveAlphaBakedIn() {
        // argb is the final rendered color used by both the swatch and the reader — no runtime
        // transformation applied. Verify every entry is translucent (not fully opaque).
        for (color in ReadaloudHighlightColor.entries) {
            val a = alpha(color.argb)
            assertTrue("${color.name} alpha $a should be translucent", a in 1..254)
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
