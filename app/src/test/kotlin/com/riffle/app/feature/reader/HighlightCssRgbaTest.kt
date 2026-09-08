package com.riffle.app.feature.reader

import com.riffle.core.models.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android counterpart of the iOS colour-channel tests in
 * `iosApp/iosAppTests/AnnotationTests.swift` (`testHexColorRed/Green/Blue`): iOS pins
 * `UIColor(hex:)` channel parsing for decoration tint; the Android mirror is the
 * ARGB → CSS `rgba()` conversion injected into the WebView. Pins each channel's
 * extraction and the round-trip for every persisted [HighlightColor] token.
 */
class HighlightCssRgbaTest {

    @Test
    fun `pure red maps to rgba with red channel only`() {
        assertEquals("rgba(255,0,0,1.00)", 0xFFFF0000.toInt().toCssRgba())
    }

    @Test
    fun `pure green maps to rgba with green channel only`() {
        assertEquals("rgba(0,255,0,1.00)", 0xFF00FF00.toInt().toCssRgba())
    }

    @Test
    fun `pure blue maps to rgba with blue channel only`() {
        assertEquals("rgba(0,0,255,1.00)", 0xFF0000FF.toInt().toCssRgba())
    }

    @Test
    fun `alpha channel is scaled to unit range`() {
        assertEquals("rgba(255,0,0,0.50)", 0x80FF0000.toInt().toCssRgba())
    }

    @Test
    fun `toCssRgbaWithAlpha overrides the encoded alpha`() {
        assertEquals("rgba(255,0,0,0.35)", 0xFFFF0000.toInt().toCssRgbaWithAlpha(0.35))
    }

    @Test
    fun `every highlight color token round-trips its argb channels through css rgba`() {
        val rgbaPattern = Regex("""rgba\((\d+),(\d+),(\d+),([\d.]+)\)""")
        for (color in HighlightColor.entries) {
            val css = color.argb.toCssRgba()
            val match = requireNotNull(rgbaPattern.matchEntire(css)) {
                "${color.name} produced malformed css: $css"
            }
            val (r, g, b) = match.destructured
            assertEquals("${color.name} red channel", color.argb ushr 16 and 0xFF, r.toInt())
            assertEquals("${color.name} green channel", color.argb ushr 8 and 0xFF, g.toInt())
            assertEquals("${color.name} blue channel", color.argb and 0xFF, b.toInt())
        }
    }
}
