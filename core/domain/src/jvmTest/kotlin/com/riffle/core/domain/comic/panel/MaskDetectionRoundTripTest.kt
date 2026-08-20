package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.imageio.ImageIO

/**
 * Verifies that panel-detector regression masks generated from real pages remain directly
 * detectable when loaded through the same black/white encoding used by issue reports (ADR 0062).
 */
class MaskDetectionRoundTripTest {

    private val detector = PanelDetector()

    @Test
    fun `panel count is preserved after binarize round-trip on real pages`() {
        val pages = listOf(
            TestMask("fixture-a", "panel-detection-fixtures/issue-773-fixture-a.png", expectedPanels = 14),
            TestMask("fixture-b", "panel-detection-fixtures/issue-773-fixture-b.png", expectedPanels = 6),
        )
        for (page in pages) {
            val maskGrid = loadMaskFixture(page.resourcePath)
            val fromMask = detector.detect(maskGrid, 0, maskGrid.width, maskGrid.height)

            assertEquals(
                "${page.name}: expected panel count from binarized issue 773 mask",
                page.expectedPanels,
                fromMask.panels.size,
            )
        }
    }

    private fun loadMaskFixture(resourcePath: String): PixelGrid {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Fixture not found on classpath: $resourcePath")
        val img = stream.use { ImageIO.read(it) }
            ?: error("Could not decode image at $resourcePath")
        val luma = ByteArray(img.width * img.height)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                luma[y * img.width + x] = if ((img.getRGB(x, y) and 0xFFFFFF) == 0) 0.toByte() else 255.toByte()
            }
        }
        return PixelGrid(img.width, img.height, luma)
    }

    private data class TestMask(
        val name: String,
        val resourcePath: String,
        val expectedPanels: Int,
    )
}
