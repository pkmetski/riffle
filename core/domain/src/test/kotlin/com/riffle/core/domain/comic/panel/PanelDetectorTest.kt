package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detector tests over programmatically-synthesized fixture pages. Real-page validation happens
 * at manual-verify time on the AVD — these tests pin the algorithm's behavior on the geometric
 * cases the algorithm is designed to handle (grids, T-shapes, splashes, dark backgrounds).
 */
class PanelDetectorTest {

    private val detector = PanelDetector()

    @Test
    fun `2x2 grid of solid panels yields 4 regions in row-major order`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // 4 panels with 20px gutters around and between
            canvas.rect(x = 20, y = 20, w = 170, h = 250, color = DARK)
            canvas.rect(x = 210, y = 20, w = 170, h = 250, color = DARK)
            canvas.rect(x = 20, y = 290, w = 170, h = 250, color = DARK)
            canvas.rect(x = 210, y = 290, w = 170, h = 250, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(4, result.panels.size)
        // Every returned region should live inside one of the source rects.
        val expected = listOf(
            Pair(20, 20), Pair(210, 20), Pair(20, 290), Pair(210, 290),
        )
        for ((ex, ey) in expected) {
            assertTrue(
                "no returned panel matches expected origin ($ex, $ey): got ${result.panels}",
                result.panels.any { p ->
                    p.x in (ex - 5)..(ex + 5) && p.y in (ey - 5)..(ey + 5)
                },
            )
        }
    }

    @Test
    fun `hollow border panels with white interior are still detected`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Thin (3px) closed borders around each panel; interior stays light.
            canvas.hollowRect(x = 20, y = 20, w = 170, h = 250, borderPx = 3, color = DARK)
            canvas.hollowRect(x = 210, y = 20, w = 170, h = 250, borderPx = 3, color = DARK)
            canvas.hollowRect(x = 20, y = 290, w = 170, h = 250, borderPx = 3, color = DARK)
            canvas.hollowRect(x = 210, y = 290, w = 170, h = 250, borderPx = 3, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(4, result.panels.size)
    }

    @Test
    fun `T-shape layout yields 3 regions`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // One wide panel across the top, two panels underneath.
            canvas.rect(x = 20, y = 20, w = 360, h = 250, color = DARK)
            canvas.rect(x = 20, y = 290, w = 170, h = 250, color = DARK)
            canvas.rect(x = 210, y = 290, w = 170, h = 250, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(3, result.panels.size)
    }

    @Test
    fun `splash covering the whole page falls back`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // One panel spanning almost the entire page.
            canvas.rect(x = 5, y = 5, w = 390, h = 550, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Fallback, result.source)
        assertEquals(1, result.panels.size)
        assertEquals(0, result.panels[0].x)
        assertEquals(0, result.panels[0].y)
        assertEquals(400, result.panels[0].width)
        assertEquals(560, result.panels[0].height)
    }

    @Test
    fun `blank light page falls back`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
        }

        val result = detector.detect(grid, pageIndex = 3, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Fallback, result.source)
        assertEquals(3, result.pageIndex)
        assertEquals(1, result.panels.size)
    }

    @Test
    fun `blank dark page falls back after inversion attempt`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Fallback, result.source)
    }

    @Test
    fun `dark-gutter page with textured panel interiors is detected without inversion`() {
        // Regression for the real-world failure on dark-tone comics (Fables etc.): jet-black
        // gutter AND jet-black between-panel space, panels have no drawn border, panel interiors
        // are modulated colour (dark shadows + bright figures). Content-vs-background binarize
        // catches the bright bits; the 5x5 promotion pass fills in panel-interior shadows that
        // happen to match the background luma; flood-fill stops at the panel edges.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = DARK)
            // Six panels in a 3x2 grid, each with a fine dither of dark + mid pixels to simulate
            // modulated content on a dark background (approximates halftone / ink texture).
            for (row in 0..1) {
                for (col in 0..2) {
                    val x = 20 + col * 130
                    val y = 20 + row * 270
                    canvas.ditheredRect(x = x, y = y, w = 110, h = 250, base = DARK, accent = 160.toByte())
                }
            }
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(6, result.panels.size)
    }

    @Test
    fun `dark-background page with light panels detects panels`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = DARK)
            // Light-fill panels on dark background: auto-invert should treat this as if the bg
            // were light and the panels dark.
            canvas.rect(x = 20, y = 20, w = 170, h = 250, color = LIGHT)
            canvas.rect(x = 210, y = 20, w = 170, h = 250, color = LIGHT)
            canvas.rect(x = 20, y = 290, w = 170, h = 250, color = LIGHT)
            canvas.rect(x = 210, y = 290, w = 170, h = 250, color = LIGHT)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(4, result.panels.size)
    }

    @Test
    fun `tiny specks are filtered out by min-area threshold`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Two real panels plus one 4x4 speck (0.007% of page — well under 2%).
            canvas.rect(x = 20, y = 20, w = 360, h = 250, color = DARK)
            canvas.rect(x = 20, y = 290, w = 360, h = 250, color = DARK)
            canvas.rect(x = 200, y = 280, w = 4, h = 4, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(2, result.panels.size)
    }

    @Test
    fun `original-coordinate scaling handles downscaled input`() {
        // Detector receives a 400x560 grid but the original image was 1000x1400.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            canvas.rect(x = 20, y = 20, w = 170, h = 250, color = DARK)
            canvas.rect(x = 210, y = 20, w = 170, h = 250, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 1000, originalHeight = 1400)

        assertEquals(2, result.panels.size)
        // Left panel at grid-x=20 → original-x ~= 50 (20 * 1000/400).
        val expectedX = (20.0 * 1000 / 400).toInt()
        assertTrue(
            "expected returned x near $expectedX, got ${result.panels.map { it.x }}",
            result.panels.any { it.x in (expectedX - 10)..(expectedX + 10) },
        )
    }

    @Test
    fun `every returned region fits inside the original page bounds`() {
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            canvas.rect(x = 20, y = 20, w = 170, h = 250, color = DARK)
            canvas.rect(x = 210, y = 290, w = 170, h = 250, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        for (p in result.panels) {
            assertTrue(p.x >= 0)
            assertTrue(p.y >= 0)
            assertTrue(p.right <= 400)
            assertTrue(p.bottom <= 560)
        }
    }

    // --- Fixture builders ---

    private val LIGHT: Byte = 240.toByte()
    private val DARK: Byte = 20.toByte()

    private fun fixture(width: Int, height: Int, paint: (Canvas) -> Unit): PixelGrid {
        val luma = ByteArray(width * height)
        val canvas = Canvas(width, height, luma)
        paint(canvas)
        return PixelGrid(width, height, luma)
    }

    private class Canvas(val width: Int, val height: Int, val luma: ByteArray) {
        fun fill(background: Byte) {
            luma.fill(background)
        }

        fun rect(x: Int, y: Int, w: Int, h: Int, color: Byte) {
            for (yy in y until (y + h).coerceAtMost(height)) {
                for (xx in x until (x + w).coerceAtMost(width)) {
                    luma[yy * width + xx] = color
                }
            }
        }

        fun hollowRect(x: Int, y: Int, w: Int, h: Int, borderPx: Int, color: Byte) {
            // Top and bottom edges
            rect(x, y, w, borderPx, color)
            rect(x, y + h - borderPx, w, borderPx, color)
            // Left and right edges
            rect(x, y, borderPx, h, color)
            rect(x + w - borderPx, y, borderPx, h, color)
        }

        /**
         * Fill a rectangle with a 1-pixel checker of [base] and [accent] — approximates halftone
         * / ink texture inside a real comic panel. Every 5x5 window contains ~13 of each colour,
         * so the promotion pass classifies near-bg pixels as content when the accent pixels
         * differ from bg.
         */
        fun ditheredRect(x: Int, y: Int, w: Int, h: Int, base: Byte, accent: Byte) {
            for (yy in y until (y + h).coerceAtMost(height)) {
                for (xx in x until (x + w).coerceAtMost(width)) {
                    luma[yy * width + xx] = if ((xx + yy) % 2 == 0) base else accent
                }
            }
        }
    }
}
