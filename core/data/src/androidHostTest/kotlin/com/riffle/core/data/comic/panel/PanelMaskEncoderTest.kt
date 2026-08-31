package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelBinaryMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelMaskEncoderTest {

    @Test
    fun `toArgbPixels maps content to black and gutter to white`() {
        // 4x2: row 0 all gutter (0), row 1 all content (1)
        val data = ByteArray(8) { idx -> if (idx / 4 == 1) 1 else 0 }
        val mask = PanelBinaryMask(width = 4, height = 2, data = data)

        val pixels = PanelMaskEncoder.toArgbPixels(mask)

        assertEquals(8, pixels.size)
        // Row 0 (gutter) → white
        repeat(4) { i -> assertEquals("pixel $i should be white", 0xFFFFFFFF.toInt(), pixels[i]) }
        // Row 1 (content) → black
        repeat(4) { i -> assertEquals("pixel ${4 + i} should be black", 0xFF000000.toInt(), pixels[4 + i]) }
    }

    @Test
    fun `toArgbPixels produces correct size for arbitrary dimensions`() {
        val mask = PanelBinaryMask(width = 7, height = 5, data = ByteArray(35))
        val pixels = PanelMaskEncoder.toArgbPixels(mask)
        assertEquals(35, pixels.size)
    }

    @Test
    fun `toArgbPixels all-gutter mask is all white`() {
        val mask = PanelBinaryMask(width = 3, height = 3, data = ByteArray(9) { 0 })
        val pixels = PanelMaskEncoder.toArgbPixels(mask)
        assertTrue("all pixels should be white", pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `toArgbPixels all-content mask is all black`() {
        val mask = PanelBinaryMask(width = 3, height = 3, data = ByteArray(9) { 1 })
        val pixels = PanelMaskEncoder.toArgbPixels(mask)
        assertTrue("all pixels should be black", pixels.all { it == 0xFF000000.toInt() })
    }
}
