package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PanelMaskBinarizerTest {

    private val LIGHT: Byte = 240.toByte()
    private val DARK: Byte = 20.toByte()

    @Test
    fun `binarize returns non-null with correct dimensions for a two-panel page`() {
        val grid = lightPage(width = 400, height = 560) { luma ->
            rect(luma, 400, x = 20, y = 20, w = 170, h = 250, color = DARK)
            rect(luma, 400, x = 210, y = 20, w = 170, h = 250, color = DARK)
        }

        val mask = PanelMaskBinarizer.binarize(grid)

        assertNotNull(mask)
        assertEquals(400, mask!!.width)
        assertEquals(560, mask.height)
        assertEquals(400 * 560, mask.data.size)
        assertEquals(1.toByte(), mask.data[25 * 400 + 25])  // inside panel = content
        assertEquals(0.toByte(), mask.data[0])               // top-left corner = gutter
    }

    @Test
    fun `binarize returns null for a blank page`() {
        val luma = ByteArray(400 * 560) { LIGHT }
        val grid = PixelGrid(400, 560, luma)
        assertNull(PanelMaskBinarizer.binarize(grid))
    }

    // --- helpers ---

    private fun lightPage(width: Int, height: Int, paint: (ByteArray) -> Unit): PixelGrid {
        val luma = ByteArray(width * height) { LIGHT }
        paint(luma)
        return PixelGrid(width, height, luma)
    }

    private fun rect(luma: ByteArray, stride: Int, x: Int, y: Int, w: Int, h: Int, color: Byte) {
        for (row in y until y + h) for (col in x until x + w) luma[row * stride + col] = color
    }
}
