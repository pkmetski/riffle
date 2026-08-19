package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Verifies that the panel detector produces the same result when run on the binary mask
 * (as uploaded to GitHub issues) as when run on the original page (ADR 0062).
 *
 * Requires real comic page images in /tmp/. Skips gracefully when absent so CI stays green.
 */
class MaskDetectionRoundTripTest {

    private val detector = PanelDetector()

    @Test
    fun `panel count is preserved after binarize round-trip on real pages`() {
        val pages = listOf(
            "tintin" to File("/tmp/tintin_p5.jpg"),
            "rising" to File("/tmp/rising_p8.jpg"),
        )
        var tested = 0
        for ((name, file) in pages) {
            if (!file.exists()) { println("SKIP $name (${file.path} not found)"); continue }
            val src = ImageIO.read(file)
            val original = src.toPixelGrid()
            val groundTruth = detector.detect(original, 0, src.width, src.height)

            val mask = PanelMaskBinarizer.binarize(original)
                ?: error("$name: binarize returned null")
            val maskGrid = mask.toPixelGrid()
            val fromMask = detector.detect(maskGrid, 0, src.width, src.height)

            println("$name: gt=${groundTruth.panels.size} mask=${fromMask.panels.size} source=${fromMask.source}")
            assertEquals(
                "$name: panel count must survive the binarize round-trip",
                groundTruth.panels.size,
                fromMask.panels.size,
            )
            tested++
        }
        if (tested == 0) println("WARNING: no real-page fixtures found in /tmp/ — skipped all checks")
    }

    private fun BufferedImage.toPixelGrid(): PixelGrid {
        val luma = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = getRGB(x, y)
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            luma[y * width + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
        }
        return PixelGrid(width, height, luma)
    }

    private fun PanelBinaryMask.toPixelGrid(): PixelGrid {
        val luma = ByteArray(width * height) { i ->
            if (data[i] == 1.toByte()) 20 else 230.toByte().toInt().toByte()
        }
        return PixelGrid(width, height, luma)
    }
}
