package com.riffle.app.feature.reader

import com.riffle.core.models.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the single-source invariant: [HighlightColor.argb] carries the FINAL rendered ARGB
 * (colour + alpha), used verbatim by both the settings swatch and every reader decoration path.
 * If these expectations drift, the picker preview will stop matching what appears on the page.
 */
class HighlightTintTest {

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `every palette entry bakes in a single non-full alpha`() {
        for (color in HighlightColor.entries) {
            val a = alpha(color.argb)
            assertEquals("${color.name} alpha must be baked-in (translucent), got 0x${a.toString(16)}", 0x80, a)
        }
    }
}
