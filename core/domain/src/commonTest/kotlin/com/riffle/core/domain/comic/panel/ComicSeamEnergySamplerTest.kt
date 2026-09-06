package com.riffle.core.domain.comic.panel

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class ComicSeamEnergySamplerTest {

    private val sqrt3 = sqrt(3f)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val black = argb(0, 0, 0)
    private val white = argb(255, 255, 255)
    private val gray = argb(128, 128, 128)

    /**
     * 3×2 image (scale 1:1 with original dims):
     *   row0: black  white  gray
     *   row1: black  black  gray
     */
    private fun image() = ColorPageImage(
        width = 3,
        height = 2,
        argb = intArrayOf(
            black, white, gray,
            black, black, gray,
        ),
    )

    @Test
    fun `column energies scan vertical gradients per column`() {
        val sampler = comicSeamEnergySampler(image(), imageWidth = 3, imageHeight = 2)
        val energies = sampler(PanelRegion(0, 0, 3, 2), true)
        assertEquals(3, energies.size)
        // col0: black→black = 0; col1: white→black = 255√3; col2: gray→gray = 0
        assertEquals(0f, energies[0], 0.01f)
        assertEquals(255f * sqrt3, energies[1], 0.01f)
        assertEquals(0f, energies[2], 0.01f)
    }

    @Test
    fun `row energies scan horizontal gradients per row`() {
        val sampler = comicSeamEnergySampler(image(), imageWidth = 3, imageHeight = 2)
        val energies = sampler(PanelRegion(0, 0, 3, 2), false)
        assertEquals(2, energies.size)
        // row0: black→white (255√3) + white→gray (127√3); row1: black→black (0) + black→gray (128√3)
        assertEquals(255f * sqrt3 + 127f * sqrt3, energies[0], 0.01f)
        assertEquals(128f * sqrt3, energies[1], 0.01f)
    }

    @Test
    fun `panel coordinates are scaled from original into the downsampled image`() {
        // Original page reported as 6×4; decoded image is 3×2 → scale 0.5 on both axes.
        // A full-height panel at original x=2,width=2 maps to decoded col 1 (bmpH=2 covers both rows).
        val sampler = comicSeamEnergySampler(image(), imageWidth = 6, imageHeight = 4)
        val energies = sampler(PanelRegion(2, 0, 2, 4), true)
        // bmpX = floor(2*0.5)=1, bmpW = floor(2*0.5)=1 → single column (decoded col 1)
        assertEquals(1, energies.size)
        // decoded col1: white→black = 255√3
        assertEquals(255f * sqrt3, energies[0], 0.01f)
    }

    @Test
    fun `column scan with fewer than two rows yields zero-energy array`() {
        // Single-row region (height 1) has no vertical neighbours to diff.
        val sampler = comicSeamEnergySampler(image(), imageWidth = 3, imageHeight = 2)
        val energies = sampler(PanelRegion(0, 0, 3, 1), true)
        assertEquals(3, energies.size)
        energies.forEach { assertEquals(0f, it, 0.0f) }
    }
}
