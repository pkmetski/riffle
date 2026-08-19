package com.riffle.core.domain.comic.panel

import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * For each sanitization technique, applies it to two real comic pages, saves the result PNG
 * at full binary-mask resolution, then runs the panel detector on the sanitized input and
 * compares to the ground-truth detection from the original page.
 *
 * Run manually; writes output to /tmp/sanity_*.
 */
class SanitizationResolutionTest {

    private val detector = PanelDetector()

    @Test
    fun sanitizationVsDetectionQuality() {
        val pages = listOf(
            "tintin" to File("/tmp/tintin_p5.jpg"),
            "rising" to File("/tmp/rising_p8.jpg"),
        )
        for ((name, file) in pages) {
            if (!file.exists()) { println("SKIP $name — ${file.path} not found"); continue }
            println("\n=== $name (${file.name}) ===")
            evaluate(name, file)
        }
    }

    private fun evaluate(name: String, file: File) {
        val src = ImageIO.read(file)
        val grid = toPixelGrid(src)
        val groundTruth = detector.detect(grid, 0, src.width, src.height)
        println("Ground truth: ${groundTruth.panels.size} panels, source=${groundTruth.source}")
        groundTruth.panels.forEachIndexed { i, p ->
            println("  [$i] x=${p.x} y=${p.y} w=${p.width} h=${p.height}")
        }

        val rawMask = detector.binarizeMask(grid)
        if (rawMask == null) { println("binarizeMask returned null, skipping"); return }

        val techniques = listOf(
            "1_binary_full"     to { maskToGrid(rawMask) },
            "2_block_4px"       to { maskToGrid(rawMask.blockQuantize(4)) },
            "3_block_8px"       to { maskToGrid(rawMask.blockQuantize(8)) },
            "4_block_16px"      to { maskToGrid(rawMask.blockQuantize(16)) },
            "5_block_32px"      to { maskToGrid(rawMask.blockQuantize(32)) },
            "6_panel_rects"     to { panelRectsGrid(src.width, src.height, groundTruth.panels) },
        )

        for ((tech, gridFn) in techniques) {
            val techGrid = gridFn()
            val result = detector.detect(techGrid, 0, src.width, src.height)
            val match = result.panels.size == groundTruth.panels.size
            val status = if (match) "✓" else "✗ (got ${result.panels.size}, want ${groundTruth.panels.size})"
            println("  $tech: $status  source=${result.source}")

            // Save the sanitized image for visual inspection
            val png = maskToPng(techGrid, src.width, src.height)
            ImageIO.write(png, "PNG", File("/tmp/sanity_${name}_${tech}.png"))
        }
    }

    // Convert a PanelBinaryMask to a PixelGrid (content=DARK, gutter=LIGHT)
    private fun maskToGrid(mask: PanelBinaryMask): PixelGrid {
        val luma = ByteArray(mask.width * mask.height)
        for (i in luma.indices) {
            luma[i] = if (mask.data[i] == 1.toByte()) 20.toByte() else 230.toByte()
        }
        return PixelGrid(mask.width, mask.height, luma)
    }

    // Panel rectangles as a PixelGrid at the original page resolution
    private fun panelRectsGrid(w: Int, h: Int, panels: List<PanelRegion>): PixelGrid {
        val luma = ByteArray(w * h); luma.fill(230.toByte())  // all gutter initially
        for (p in panels) {
            for (y in p.y until (p.y + p.height).coerceAtMost(h)) {
                for (x in p.x until (p.x + p.width).coerceAtMost(w)) {
                    luma[y * w + x] = 20  // content
                }
            }
        }
        return PixelGrid(w, h, luma)
    }

    // Render a PixelGrid back to a BufferedImage scaled to (displayW x displayH)
    private fun maskToPng(grid: PixelGrid, displayW: Int, displayH: Int): BufferedImage {
        val small = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val v = grid.get(x, y)
            small.setRGB(x, y, Color(v, v, v).rgb)
        }
        if (grid.width == displayW && grid.height == displayH) return small
        val out = BufferedImage(displayW, displayH, BufferedImage.TYPE_BYTE_GRAY)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(small, 0, 0, displayW, displayH, null)
        g.dispose()
        return out
    }

    private fun toPixelGrid(img: BufferedImage): PixelGrid {
        val luma = ByteArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            luma[y * img.width + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
        }
        return PixelGrid(img.width, img.height, luma)
    }
}
