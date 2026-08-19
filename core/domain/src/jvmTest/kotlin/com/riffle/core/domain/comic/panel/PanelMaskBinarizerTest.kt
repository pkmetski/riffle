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

    @Test
    fun `binarize output is detectable — panel detector finds the same panels on the mask as on the original`() {
        // Regression: the mask fed back into the detector must yield the same panel count as
        // running detection on the original grid. If binarizeMask drifts from the detection
        // binarize() assumptions this test will catch it.
        val W = 400; val H = 560
        val original = lightPage(width = W, height = H) { luma ->
            rect(luma, W, x = 20,  y = 20,  w = 170, h = 250, color = DARK)
            rect(luma, W, x = 210, y = 20,  w = 170, h = 250, color = DARK)
            rect(luma, W, x = 20,  y = 290, w = 170, h = 250, color = DARK)
            rect(luma, W, x = 210, y = 290, w = 170, h = 250, color = DARK)
        }
        val detector = PanelDetector()
        val expected = detector.detect(original, 0, W, H)
        assertEquals("ground truth must be 4 panels", 4, expected.panels.size)

        val mask = PanelMaskBinarizer.binarize(original)!!
        // Re-encode mask as a PixelGrid with the same two-value luma palette the fixtures use.
        val maskGrid = PixelGrid(mask.width, mask.height, ByteArray(mask.data.size) { i ->
            if (mask.data[i] == 1.toByte()) 20 else 230.toByte().toInt().toByte()
        })
        val fromMask = detector.detect(maskGrid, 0, W, H)

        assertEquals(
            "detector must find the same panel count on the mask as on the original page",
            expected.panels.size,
            fromMask.panels.size,
        )
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
