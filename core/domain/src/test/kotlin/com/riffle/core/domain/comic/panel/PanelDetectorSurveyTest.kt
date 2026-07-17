package com.riffle.core.domain.comic.panel

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Survey harness — iterates every fixture in `comic-panel-fixtures/` (gitignored — populated
 * locally from ABS) and produces:
 *
 *   1. per-page debug PNGs under `build/panel-detector-debug/survey/` (input, binarize mask,
 *      gutter flood-fill, bboxes overlay);
 *   2. a Markdown table under `build/panel-detector-debug/survey/report.md` with source /
 *      panel count / coverage / a one-line human summary per page.
 *
 * Not a pass/fail test — it's a diagnostic to drive iteration on the detector against a broad
 * cross-style sample. Skips cleanly if no fixtures are present.
 */
class PanelDetectorSurveyTest {

    private val detector = PanelDetector()

    @Test
    fun `survey every fixture`() {
        val url = javaClass.classLoader.getResource("comic-panel-fixtures/")
        assumeTrue("no fixtures dir on classpath", url != null)
        val resDir = File(url!!.toURI())
        val fixtures = resDir.listFiles()
            ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assumeTrue("no fixtures on classpath", fixtures.isNotEmpty())

        val outDir = File("build/panel-detector-debug/survey").also {
            it.deleteRecursively()
            it.mkdirs()
        }

        val rows = mutableListOf<Row>()
        for (fixture in fixtures) {
            val loaded = decode(fixture) ?: continue
            val prefix = fixture.nameWithoutExtension
            val result = detector.detect(loaded.grid, 0, loaded.originalWidth, loaded.originalHeight)
            val trace = detector.detectDebug(loaded.grid)
            dumpDebugPngs(prefix, outDir, loaded.grid, trace)
            val pageArea = loaded.originalWidth.toLong() * loaded.originalHeight.toLong()
            val coverage = if (result.source == PanelSource.Fallback) 0.0
                else result.panels.sumOf { it.area() }.toDouble() / pageArea.toDouble()
            rows.add(Row(
                prefix = prefix,
                width = loaded.originalWidth,
                height = loaded.originalHeight,
                source = result.source,
                panelCount = if (result.source == PanelSource.Fallback) 0 else result.panels.size,
                coverage = coverage,
                rawComponents = trace.componentBboxes.size,
                filteredComponents = trace.filteredBboxes.size,
            ))
        }

        val report = buildString {
            appendLine("# Panel detector survey (${rows.size} pages)")
            appendLine()
            val worked = rows.count { it.source == PanelSource.Auto && it.panelCount >= 2 }
            val fallback = rows.count { it.source == PanelSource.Fallback }
            appendLine("**Summary**: ${worked}/${rows.size} pages produced ≥2 detected panels; ${fallback}/${rows.size} fell back to Fit Whole.")
            appendLine()
            appendLine("| Fixture | Size | Source | Panels | Coverage | CC raw→filtered |")
            appendLine("|---|---|---|---|---|---|")
            for (r in rows) {
                val cov = "%.0f%%".format(r.coverage * 100)
                appendLine("| ${r.prefix} | ${r.width}x${r.height} | ${r.source} | ${r.panelCount} | $cov | ${r.rawComponents}→${r.filteredComponents} |")
            }
            appendLine()
            appendLine("Debug PNGs in this directory: <prefix>_00_input.png, _01_binarize.png, _02_gutter.png, _03_bboxes.png")
        }
        val reportFile = File(outDir, "report.md")
        reportFile.writeText(report)
        println("Survey complete. Report: ${reportFile.absolutePath}")
        println("$outDir")
        println(report)
    }

    // --- shared with PanelDetectorRealImageTest infrastructure ---

    private data class Loaded(val grid: PixelGrid, val originalWidth: Int, val originalHeight: Int)
    private data class Row(
        val prefix: String,
        val width: Int,
        val height: Int,
        val source: PanelSource,
        val panelCount: Int,
        val coverage: Double,
        val rawComponents: Int,
        val filteredComponents: Int,
    )

    private fun decode(file: File): Loaded? {
        val src = try { ImageIO.read(file) ?: return null } catch (_: Throwable) { return null }
        val origW = src.width
        val origH = src.height
        val targetLongEdge = 1000
        val longEdge = maxOf(origW, origH)
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        val downW = origW / sample
        val downH = origH / sample
        val scaled = BufferedImage(downW, downH, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(src, 0, 0, downW, downH, null)
        g.dispose()
        val luma = ByteArray(downW * downH)
        for (y in 0 until downH) for (x in 0 until downW) {
            val c = Color(scaled.getRGB(x, y))
            val y601 = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue).toInt().coerceIn(0, 255)
            luma[y * downW + x] = y601.toByte()
        }
        return Loaded(PixelGrid(downW, downH, luma), origW, origH)
    }

    private fun dumpDebugPngs(prefix: String, outDir: File, grid: PixelGrid, trace: PanelDetector.DebugTrace) {
        val src = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            val v = grid.get(x, y)
            src.setRGB(x, y, Color(v, v, v).rgb)
        }
        ImageIO.write(src, "png", File(outDir, "${prefix}_00_input.png"))
        trace.binaryMaskData?.let { data ->
            val bin = BufferedImage(trace.binaryMaskWidth, trace.binaryMaskHeight, BufferedImage.TYPE_INT_ARGB)
            for (i in data.indices) {
                val on = data[i] == 1.toByte()
                bin.setRGB(i % trace.binaryMaskWidth, i / trace.binaryMaskWidth,
                    if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
            ImageIO.write(bin, "png", File(outDir, "${prefix}_01_binarize.png"))
        }
        if (trace.croppedMaskData != null && trace.gutterMask != null) {
            val cw = trace.croppedWidth
            val ch = trace.croppedHeight
            val overlay = BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB)
            for (i in 0 until cw * ch) {
                overlay.setRGB(i % cw, i / cw, if (trace.gutterMask!![i]) 0xFF2244AA.toInt() else 0xFFAA2222.toInt())
            }
            ImageIO.write(overlay, "png", File(outDir, "${prefix}_02_gutter.png"))
        }
        val boxed = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
        val gg = boxed.createGraphics()
        gg.drawImage(src, 0, 0, null)
        gg.stroke = BasicStroke(2f)
        gg.color = Color(255, 255, 0, 200)
        for (b in trace.componentBboxes) {
            val x = b[0] + trace.croppedOffsetX
            val y = b[1] + trace.croppedOffsetY
            gg.drawRect(x, y, b[2] - b[0], b[3] - b[1])
        }
        gg.color = Color(0, 255, 0, 220)
        gg.stroke = BasicStroke(3f)
        for (b in trace.filteredBboxes) {
            val x = b[0] + trace.croppedOffsetX
            val y = b[1] + trace.croppedOffsetY
            gg.drawRect(x, y, b[2] - b[0], b[3] - b[1])
        }
        gg.dispose()
        ImageIO.write(boxed, "png", File(outDir, "${prefix}_03_bboxes.png"))
    }
}
