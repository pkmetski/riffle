package com.riffle.core.domain.comic.panel

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the detector against real comic-page fixtures loaded from disk (screenshots the user
 * reported as broken). The JVM has ImageIO + BufferedImage, so we can reproduce the same
 * decoding pipeline the Android decoder does — downscale to ~1000 px on the long edge, convert
 * to BT.601 luma, feed to [PanelDetector] — without an emulator.
 *
 * Dumps intermediate masks (binarize, gutter flood-fill, candidate bboxes) as PNGs under
 * `build/panel-detector-debug/` so a failing run leaves visual evidence of which stage broke.
 *
 * If one of these fails, the detector is genuinely broken for that class of page; not a cache
 * or UI-plumbing issue.
 */
class PanelDetectorRealImageTest {

    private val detector = PanelDetector()

    @Test
    fun `dark-gutter 3x2 grid from user attachment is detected as multiple panels`() {
        val (grid, w, h) = loadFixture("comic-panel-fixtures/dark_gutter_3x2.png", trimLetterbox = true)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = w, originalHeight = h)
        dumpTrace("dark_gutter_3x2", grid, detector.detectDebug(grid))
        printResult("dark_gutter_3x2", result)
        assertNotEquals(PanelSource.Fallback, result.source)
        assertTrue("expected ≥ 4 panels", result.panels.size >= 4)
    }

    /**
     * Traditional multi-panel page from Batman: The Black Mirror (CBZ id
     * 97b297ad-d873-44c0-9935-4e9be9e4ce0f). Light-blue gutter with drawn black panel borders —
     * exactly the layout the projection detector was built for.
     */
    @Test
    fun `batman p050 traditional grid detects multiple panels`() {
        val (grid, w, h) = loadFixture("comic-panel-fixtures/batman_p050.jpg")
        val result = detector.detect(grid, pageIndex = 0, originalWidth = w, originalHeight = h)
        dumpTrace("batman_p050", grid, detector.detectDebug(grid))
        printResult("batman_p050", result)
        assertNotEquals(PanelSource.Fallback, result.source)
        assertTrue("expected ≥ 4 panels", result.panels.size >= 4)
    }

    @Test
    fun `batman p150 traditional grid detects multiple panels`() {
        val (grid, w, h) = loadFixture("comic-panel-fixtures/batman_p150.jpg")
        val result = detector.detect(grid, pageIndex = 0, originalWidth = w, originalHeight = h)
        dumpTrace("batman_p150", grid, detector.detectDebug(grid))
        printResult("batman_p150", result)
        assertNotEquals(PanelSource.Fallback, result.source)
        assertTrue("expected ≥ 2 panels", result.panels.size >= 2)
    }

    /**
     * Documented known limitation: on pages where traditional rectangular panels are drawn on
     * top of a bleed-splash background, the gutter-flood-fill approach can't find them (there's
     * no outer gutter to flood-fill from — the splash extends to every page edge). Detecting
     * these would require drawn-border detection (edge/contour matching) instead of gutter
     * detection. These assertions pin the CURRENT behaviour so a future border-detection path
     * flips them to positive (and this test flips red on regression, prompting an update).
     */
    @Test
    fun `batman p020 mixed traditional-plus-bleed currently returns Fallback`() {
        val result = detectAndReport("batman_p020", "comic-panel-fixtures/batman_p020.jpg")
        assertEquals("known limitation: drawn borders on a bleed splash — see build/panel-detector-debug/batman_p020_*.png",
            PanelSource.Fallback, result.source)
    }

    @Test
    fun `batman p200 mixed traditional-plus-bleed currently returns Fallback`() {
        val result = detectAndReport("batman_p200", "comic-panel-fixtures/batman_p200.jpg")
        assertEquals("known limitation: drawn borders on a bleed splash — see build/panel-detector-debug/batman_p200_*.png",
            PanelSource.Fallback, result.source)
    }

    /**
     * A pure full-page splash is CORRECTLY detected as one panel — Fallback + one region is the
     * intended behaviour for splashes (Panel View just shows Fit Whole for that page).
     */
    @Test
    fun `batman p085 full-page splash correctly returns single panel`() {
        val (grid, w, h) = loadFixture("comic-panel-fixtures/batman_p085.jpg")
        val result = detector.detect(grid, pageIndex = 0, originalWidth = w, originalHeight = h)
        dumpTrace("batman_p085", grid, detector.detectDebug(grid))
        printResult("batman_p085", result)
        assertEquals("full-page splash should return exactly one region", 1, result.panels.size)
    }

    private fun detectAndReport(prefix: String, resource: String): PagePanels {
        val (grid, w, h) = loadFixture(resource)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = w, originalHeight = h)
        dumpTrace(prefix, grid, detector.detectDebug(grid))
        printResult(prefix, result)
        return result
    }

    private fun printResult(prefix: String, result: PagePanels) {
        println("$prefix → source=${result.source} panels=${result.panels.size}")
        result.panels.forEachIndexed { i, p ->
            println("  panel[$i] = (${p.x},${p.y})..(${p.right},${p.bottom})")
        }
    }

    // --- ImageIO helper ---

    private data class LoadedGrid(
        val grid: PixelGrid,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    private fun loadFixture(resourcePath: String, trimLetterbox: Boolean = false): LoadedGrid {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
        // batman_*.jpg fixtures are gitignored (DC copyright); JUnit assume-skip when absent so
        // fresh checkouts don't fail — devs who want to iterate on the detector copy pages into
        // core/domain/src/test/resources/comic-panel-fixtures/ locally.
        assumeTrue("fixture not present on classpath: $resourcePath (add locally if iterating)", stream != null)
        stream!!
        val rawSource = stream.use { ImageIO.read(it) }
            ?: error("ImageIO failed to decode $resourcePath")
        // Screenshots from the reader include the reader's black letterbox top/bottom. Trim it
        // so we feed the detector approximately what the on-device pipeline sees (the raw CBZ
        // page bytes, before the reader lays them out inside the viewport). Raw CBZ page images
        // pass trimLetterbox = false.
        val source = if (trimLetterbox) trimBlackLetterbox(rawSource) else rawSource
        val originalW = source.width
        val originalH = source.height

        // Match AndroidPageImageDecoder: downscale so the long edge is roughly targetLongEdge.
        val targetLongEdge = 1000
        val longEdge = maxOf(originalW, originalH)
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2

        val downW = originalW / sample
        val downH = originalH / sample
        val scaled = BufferedImage(downW, downH, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(source, 0, 0, downW, downH, null)
        g.dispose()

        val luma = ByteArray(downW * downH)
        for (y in 0 until downH) {
            for (x in 0 until downW) {
                val c = Color(scaled.getRGB(x, y))
                val y601 = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).toInt().coerceIn(0, 255)
                luma[y * downW + x] = y601.toByte()
            }
        }
        return LoadedGrid(
            grid = PixelGrid(downW, downH, luma),
            originalWidth = originalW,
            originalHeight = originalH,
        )
    }

    private operator fun LoadedGrid.component1() = grid
    private operator fun LoadedGrid.component2() = originalWidth
    private operator fun LoadedGrid.component3() = originalHeight

    /**
     * Trim near-black borders from the top / bottom / left / right of the image so we test
     * against approximately what the on-device pipeline sees (the raw CBZ page, before the
     * reader's letterbox is added). A row/column is "black" if every pixel is below a small
     * luma threshold.
     */
    private fun trimBlackLetterbox(source: BufferedImage): BufferedImage {
        val w = source.width
        val h = source.height
        // Screenshots include a slight dark-grey rounded gradient outside the panels — bump the
        // threshold well above jet-black so those aren't fed to the detector as "content".
        val darkThreshold = 30

        fun rowIsBlack(y: Int): Boolean {
            for (x in 0 until w) {
                val c = Color(source.getRGB(x, y))
                val luma = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).toInt()
                if (luma > darkThreshold) return false
            }
            return true
        }

        fun colIsBlack(x: Int, y0: Int, y1: Int): Boolean {
            for (y in y0..y1) {
                val c = Color(source.getRGB(x, y))
                val luma = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).toInt()
                if (luma > darkThreshold) return false
            }
            return true
        }

        var top = 0
        while (top < h && rowIsBlack(top)) top++
        var bottom = h - 1
        while (bottom > top && rowIsBlack(bottom)) bottom--
        if (top >= bottom) return source
        var left = 0
        while (left < w && colIsBlack(left, top, bottom)) left++
        var right = w - 1
        while (right > left && colIsBlack(right, top, bottom)) right--
        if (left >= right) return source

        val cw = right - left + 1
        val ch = bottom - top + 1
        return source.getSubimage(left, top, cw, ch)
    }

    /**
     * Dump the source grid + binarize mask + flood-fill gutter + component bboxes as PNGs so a
     * failing test leaves a visual trail of what the detector saw. Writes under
     * `core/domain/build/panel-detector-debug/`.
     */
    private fun dumpTrace(prefix: String, grid: PixelGrid, trace: PanelDetector.DebugTrace) {
        val outDir = File("build/panel-detector-debug").also { it.mkdirs() }

        // Original (downscaled greyscale)
        val src = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val v = grid.get(x, y)
                src.setRGB(x, y, Color(v, v, v).rgb)
            }
        }
        ImageIO.write(src, "png", File(outDir, "${prefix}_00_input.png"))

        // Binarize mask (content=white, gutter=black)
        trace.binaryMaskData?.let { data ->
            val bin = BufferedImage(trace.binaryMaskWidth, trace.binaryMaskHeight, BufferedImage.TYPE_INT_ARGB)
            for (i in data.indices) {
                val on = data[i] == 1.toByte()
                bin.setRGB(i % trace.binaryMaskWidth, i / trace.binaryMaskWidth, if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
            ImageIO.write(bin, "png", File(outDir, "${prefix}_01_binarize.png"))
        }

        // Cropped + gutter overlay (blue=gutter, red=non-gutter)
        if (trace.croppedMaskData != null && trace.gutterMask != null) {
            val cw = trace.croppedWidth
            val ch = trace.croppedHeight
            val overlay = BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB)
            for (i in 0 until cw * ch) {
                overlay.setRGB(i % cw, i / cw, if (trace.gutterMask!![i]) 0xFF2244AA.toInt() else 0xFFAA2222.toInt())
            }
            ImageIO.write(overlay, "png", File(outDir, "${prefix}_02_gutter.png"))
        }

        // Bboxes overlaid on the greyscale input
        val boxed = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
        val g = boxed.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.stroke = BasicStroke(2f)
        g.color = Color(255, 255, 0, 200)
        for (b in trace.componentBboxes) {
            val x = b[0] + trace.croppedOffsetX
            val y = b[1] + trace.croppedOffsetY
            g.drawRect(x, y, b[2] - b[0], b[3] - b[1])
        }
        g.color = Color(0, 255, 0, 220)
        g.stroke = BasicStroke(3f)
        for (b in trace.filteredBboxes) {
            val x = b[0] + trace.croppedOffsetX
            val y = b[1] + trace.croppedOffsetY
            g.drawRect(x, y, b[2] - b[0], b[3] - b[1])
        }
        g.dispose()
        ImageIO.write(boxed, "png", File(outDir, "${prefix}_03_bboxes.png"))

        println("dumped debug PNGs to ${outDir.absolutePath}/${prefix}_*.png")
    }
}
