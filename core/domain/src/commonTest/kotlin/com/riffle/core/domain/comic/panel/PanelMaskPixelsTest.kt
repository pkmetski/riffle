package com.riffle.core.domain.comic.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved from core/data's androidHostTest PanelMaskEncoderTest (Android-only) to commonTest:
 * [panelMaskToArgbPixels] is pure logic shared by both platforms' PNG encoders (ADR 0062), so one
 * commonTest assertion covers the pixel-mapping semantics for both Android's PanelMaskEncoder and
 * iOS's IosPanelMaskEncoder.
 */
class PanelMaskPixelsTest {

    @Test
    fun `toArgbPixels maps content to black and gutter to white`() {
        // 4x2: row 0 all gutter (0), row 1 all content (1)
        val data = ByteArray(8) { idx -> if (idx / 4 == 1) 1 else 0 }
        val mask = PanelBinaryMask(width = 4, height = 2, data = data)

        val pixels = panelMaskToArgbPixels(mask)

        assertEquals(8, pixels.size)
        repeat(4) { i -> assertEquals(0xFFFFFFFF.toInt(), pixels[i], "pixel $i should be white") }
        repeat(4) { i -> assertEquals(0xFF000000.toInt(), pixels[4 + i], "pixel ${4 + i} should be black") }
    }

    @Test
    fun `toArgbPixels produces correct size for arbitrary dimensions`() {
        val mask = PanelBinaryMask(width = 7, height = 5, data = ByteArray(35))
        val pixels = panelMaskToArgbPixels(mask)
        assertEquals(35, pixels.size)
    }

    @Test
    fun `toArgbPixels all-gutter mask is all white`() {
        val mask = PanelBinaryMask(width = 3, height = 3, data = ByteArray(9) { 0 })
        val pixels = panelMaskToArgbPixels(mask)
        assertTrue(pixels.all { it == 0xFFFFFFFF.toInt() }, "all pixels should be white")
    }

    @Test
    fun `toArgbPixels all-content mask is all black`() {
        val mask = PanelBinaryMask(width = 3, height = 3, data = ByteArray(9) { 1 })
        val pixels = panelMaskToArgbPixels(mask)
        assertTrue(pixels.all { it == 0xFF000000.toInt() }, "all pixels should be black")
    }
}
