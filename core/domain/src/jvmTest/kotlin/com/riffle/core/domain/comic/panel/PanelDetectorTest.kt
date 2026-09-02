package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `bleed-splash-with-noise-islands falls back to Fit Whole`() {
        // Simulates the bleed-splash failure mode from the user video: a mostly-content page
        // (bleed art extending to every edge) with a handful of tiny noise blobs the naive CC
        // step would pick up as "panels" and force Panel View to zoom into as blurry noise.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = DARK)
            // A few small (< 15% of page) noise islands scattered around.
            canvas.rect(x = 20, y = 20, w = 30, h = 20, color = LIGHT)
            canvas.rect(x = 100, y = 200, w = 15, h = 15, color = LIGHT)
            canvas.rect(x = 350, y = 500, w = 25, h = 25, color = LIGHT)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Fallback, result.source)
        assertEquals(1, result.panels.size)
    }

    @Test
    fun `duplicate panels are deduped so the reader doesn't show the same panel twice`() {
        // Reproduces the "panel A shown alone, then panel A + panel B shown together" video
        // failure. Feed the sanity checks a synthetic list containing a tight panel and a
        // larger bbox that entirely contains it — the dedup pass keeps the tight one.
        val tight = PanelRegion(x = 20, y = 20, width = 200, height = 100)
        val merged = PanelRegion(x = 15, y = 15, width = 220, height = 220)  // contains 'tight' and more
        val other = PanelRegion(x = 20, y = 300, width = 200, height = 100)

        // Access the detector's internal machinery via a fixture that produces these regions.
        // Simplest way: build a synthetic page where these bboxes are what CC detects.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Two rectangular panels stacked vertically. If the detector produces both a tight
            // top-panel bbox AND a merged (top-panel + gutter + start of bottom-panel) bbox,
            // the dedup pass keeps only the tight one.
            canvas.rect(x = 20, y = 20, w = 360, h = 200, color = DARK)
            canvas.rect(x = 20, y = 240, w = 360, h = 200, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        // Expect exactly 2 panels — one per drawn rectangle. If dedup were absent and the
        // detector duplicated one panel, we'd see 3+ panels or heavy overlap → Fallback.
        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 2 clean panels, got ${result.panels}", 2, result.panels.size)
        for ((i, a) in result.panels.withIndex()) {
            for (b in result.panels.drop(i + 1)) {
                val smallerArea = minOf(a.area(), b.area())
                val overlap = if (a.area() <= b.area()) b.overlapFraction(a) else a.overlapFraction(b)
                assertTrue(
                    "duplicate panels leaked past dedup (overlap ${(overlap * 100).toInt()}% ≥ 60% threshold)",
                    overlap < 0.6,
                )
                // Suppress unused warning:
                if (smallerArea < 0L) fail()
            }
        }
    }

    @Test
    fun `bbox that straddles two real panels plus a gutter is split at the gutter`() {
        // Simulate a page where CC merged two side-by-side panels via a stray bridge pixel at
        // the top of the shared gutter. Without the internal-gutter split, Panel View would
        // zoom into a bbox that includes both panels + gutter — exactly the "why is this a
        // panel? it's the area between two panels" failure the user reported.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Two side-by-side panels with a wide (30px) clear vertical gutter between them.
            canvas.rect(x = 20, y = 20, w = 160, h = 500, color = DARK)
            canvas.rect(x = 220, y = 20, w = 160, h = 500, color = DARK)
            // A single 1-pixel bridge at the top connects them (the kind of stray pixel that
            // makes CC treat the pair as one component).
            canvas.rect(x = 180, y = 25, w = 40, h = 1, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 2 panels after split-at-internal-gutter, got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            2, result.panels.size)
        // Panels should each be ~160 wide, not one ~360-wide bbox spanning the gutter.
        for (p in result.panels) {
            assertTrue("panel width ${p.width} should be < 250 (i.e. not spanning both panels + gutter)", p.width < 250)
        }
    }

    @Test
    fun `page with meaningful panels but insufficient coverage falls back`() {
        // Two panels covering < 30% of the page (e.g. bleed-splash with just two small
        // rectangular insets). Coverage sanity check rejects → Fallback rather than force
        // Panel View to zoom into the two islands and skip the wider art.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Two panels ~5% of page each (roughly 10% total) — well under the 30% floor.
            canvas.rect(x = 20, y = 20, w = 90, h = 130, color = DARK)
            canvas.rect(x = 290, y = 20, w = 90, h = 130, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Fallback, result.source)
    }

    @Test
    fun `wide hollow-bordered panels are not falsely split at low-content interior rows`() {
        // Regression: splitAtInternalGutters was classifying interior rows of hollow panels as
        // "gutter" when the panel was wide (making the 5%-content threshold smaller than the 6
        // border pixels each row contributed). Result: each CC bbox was split at the balloon
        // interior, producing two tiny halves that were filtered by minHeight, leaving 0 panels.
        // Fix: use flood-fill gutter pixel count instead of content count — panel interiors are
        // not flood-fill-reachable so they never trigger a split.
        val grid = fixture(width = 600, height = 400) { canvas ->
            canvas.fill(background = LIGHT)
            // Two wide (250px) hollow panels. Wide enough that (border pixels) / width < 5%,
            // which previously triggered the false gutter classification.
            canvas.hollowRect(x = 20, y = 20, w = 250, h = 360, borderPx = 3, color = DARK)
            canvas.hollowRect(x = 300, y = 20, w = 250, h = 360, borderPx = 3, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 600, originalHeight = 400)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 2 panels (wide hollow rects), got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            2, result.panels.size)
    }

    @Test
    fun `multi-row page with sparse-interior panels detects all rows`() {
        // Regression for the user's Maus-page bug: 4 rows of panels where each panel has a
        // hollow border (white balloon interior). The top row survived in older code because
        // panels happened to be wide enough; rows 2-4 were shorter and their sub-panels fell
        // below minHeight after the false split. Fix: gutter-based split never triggers on
        // enclosed panel interiors, so all 8 panels survive.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // 4 rows × 2 panels, each hollow-bordered. Row height ~100px (much shorter than
            // the 2-row case), so sub-panels after a false split would be ~15px — well below
            // the 84px minHeight floor.
            for (row in 0 until 4) {
                val y = 20 + row * 130
                canvas.hollowRect(x = 20, y = y, w = 160, h = 110, borderPx = 3, color = DARK)
                canvas.hollowRect(x = 210, y = y, w = 160, h = 110, borderPx = 3, color = DARK)
            }
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 8 panels (4-row grid of hollow rects), got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            8, result.panels.size)
    }

    @Test
    fun `full-width splash row above two side-by-side panels yields 3 regions`() {
        // Regression for the page-56-style layout: a full-width top splash and two panels
        // side-by-side below. The splash row produces one "suspicious" full-width column band
        // in the projection; previously any suspicious row caused gridByProjection to return
        // null and hand the whole page to the CC detector.  The CC detector cannot always
        // recover this layout correctly: when the splash art meets the bottom panels with no
        // clean gutter row between them at downscale resolution, flood-fill gutter is empty,
        // the three panel CCs merge into one, splitAtInternalGutters finds only a vertical
        // split, and the result is two wrong half-height panels.
        //
        // Fix: return null only when ALL rows are suspicious.  When some rows are non-
        // suspicious, keep the suspicious rows as single full-width cells and proceed with
        // the projection — this correctly produces 1 splash + 2 bottom panels = 3 panels.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Full-width splash: spans almost the entire width so its column projection
            // contains a single band ≥ 90 % of the cropped width → suspicious row.
            canvas.rect(x = 10, y = 10, w = 380, h = 290, color = DARK)
            // Gutter gap (rows 300–319 stay LIGHT).
            // Two side-by-side panels below: the column projection for this row finds 2
            // bands → not suspicious → allSuspicious = false → projection path proceeds.
            canvas.rect(x = 10, y = 320, w = 170, h = 220, color = DARK)
            canvas.rect(x = 220, y = 320, w = 170, h = 220, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "expected 3 panels (1 splash + 2 bottom), got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            3, result.panels.size,
        )
        val topPanels = result.panels.filter { it.y < 200 }
        val bottomPanels = result.panels.filter { it.y >= 200 }
        assertEquals("expected 1 top splash panel", 1, topPanels.size)
        assertEquals("expected 2 bottom panels", 2, bottomPanels.size)
        assertTrue(
            "splash should span ≥ 80 % of page width; got ${topPanels[0].width}",
            topPanels[0].width >= 320,
        )
    }

    @Test
    fun `two full-width rows above two side-by-side panels yield 4 regions`() {
        // Regression for the "yellow papyrus" bug: a page with two consecutive full-width
        // panels (rows 1 and 2 are both suspicious) followed by a two-column row.  Previously
        // suspiciousWideRow fired on row 1 and the projection returned null; the CC path then
        // ran and detected the yellow narrator-box scroll — whose bright interior is classified
        // as gutter — as its own CC, because the scroll's border+text pixels were isolated from
        // the surrounding panel art.  deduplicateOverlapping then kept the scroll (smaller CC)
        // and dropped the larger panel-art CC, producing a wrong result where the narrator box
        // appeared as a panel and the real top panel was missing.
        //
        // Fix: null is returned only when ALL rows are suspicious.  Here row 3 is not, so the
        // projection returns 4 correct panels and the CC path (which would mis-detect the
        // narrator box) is never invoked.
        val grid = fixture(width = 400, height = 600) { canvas ->
            canvas.fill(background = LIGHT)
            // Row 1: full-width panel (column projection → 1 suspicious wide band)
            canvas.rect(x = 10, y = 10, w = 380, h = 170, color = DARK)
            // Row 2: full-width panel (also suspicious)
            canvas.rect(x = 10, y = 200, w = 380, h = 170, color = DARK)
            // Row 3: two side-by-side panels (column projection → 2 bands, not suspicious)
            canvas.rect(x = 10, y = 390, w = 170, h = 190, color = DARK)
            canvas.rect(x = 220, y = 390, w = 170, h = 190, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 600)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "expected 4 panels (2 full-width + 2 bottom), got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            4, result.panels.size,
        )
        val fullWidthRows = result.panels.filter { it.width >= 320 }
        val bottomPanels = result.panels.filter { it.width < 320 }
        assertEquals("expected 2 full-width panels", 2, fullWidthRows.size)
        assertEquals("expected 2 narrow bottom panels", 2, bottomPanels.size)
    }

    @Test
    fun `top strip with dark-bordered gutters is split into 4 panels`() {
        // Regression for the "4 top panels treated as one" bug. Layout: a narrow top strip with
        // 4 panels whose between-panel gutters have enough dark art pixels to push the column
        // projection above the 15% cutoff, so gridByProjection merges all 4 into one wide cell.
        // The detector detects the suspicious single-wide column band and falls back to CC
        // detection, which correctly finds all 5 panels via separate connected components.
        val grid = fixture(width = 600, height = 800) { canvas ->
            canvas.fill(background = LIGHT)
            // Top strip: 4 panels each 125px wide with 12px gutters. Each gutter has enough dark
            // pixels (spanning the middle portion) to push the column projection above the 15%
            // cutoff, while still leaving white background pixels reachable from the page border.
            for (i in 0 until 4) {
                canvas.rect(x = 15 + i * 137, y = 15, w = 125, h = 140, color = DARK)
            }
            for (i in 0 until 3) {
                val gx = 140 + i * 137  // first gutter column for gap i
                canvas.rect(x = gx, y = 45, w = 12, h = 80, color = DARK)
            }
            // Large panel below.
            canvas.rect(x = 15, y = 175, w = 555, h = 600, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 600, originalHeight = 800)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 5 panels (4 top strip + 1 large), got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            5, result.panels.size)
        val topPanels = result.panels.filter { it.y < 170 }
        assertEquals("expected 4 top-strip panels", 4, topPanels.size)
    }

    @Test
    fun `panel with flood-fill-accessible interior is not falsely split by projection path`() {
        // Regression: if the downscaled image has a gap in a speech-balloon border, the flood-fill
        // penetrates the white interior. The horizontal gutter check fires on interior rows
        // (scoring ~70% gutter pixels, well above the 30% threshold). Without a guard, the panel
        // would be halved; both halves could be too short to survive minPanelDimensionFraction, so
        // the first panel disappears from the reading sequence entirely.
        //
        // Guard: internalGutterMaxFraction (25%) rejects "gutters" that span more than 25% of the
        // bbox's perpendicular dimension. A flood-fill-accessible interior typically covers 50%+
        // of the panel height, far above the limit. A genuine narrow gutter (missed by the 15px
        // projection minimum) is at most a few percent — always accepted.
        //
        // Fixture: 2×2 grid. Top-left panel has a white interior (x=60..199, y=50..149) connected
        // to the top page gutter via a white channel (x=90..109, y=10..49) — this is the
        // synthetic equivalent of a downscaling gap in the outer panel border. The channel makes
        // the interior flood-fill-reachable, which would trigger the old false split.
        val grid = fixture(width = 600, height = 400) { canvas ->
            canvas.fill(background = LIGHT)
            canvas.rect(x = 10, y = 10, w = 200, h = 190, color = DARK)
            canvas.rect(x = 90, y = 10, w = 20, h = 40, color = LIGHT)   // gap → top gutter
            canvas.rect(x = 60, y = 50, w = 140, h = 100, color = LIGHT) // large interior
            canvas.rect(x = 250, y = 10, w = 200, h = 190, color = DARK)
            canvas.rect(x = 10, y = 220, w = 200, h = 170, color = DARK)
            canvas.rect(x = 250, y = 220, w = 200, h = 170, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 600, originalHeight = 400)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals("expected 4 panels, got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            4, result.panels.size)
        val topLeft = result.panels.filter { it.x < 240 && it.y < 200 }
        assertEquals("top-left panel with flood-fill-accessible interior must survive as one panel", 1, topLeft.size)
        assertTrue("top-left panel must span the full panel height, not just a falsely-split remnant",
            topLeft[0].height >= 150)
    }

    @Test
    fun `white speech bubble in gutter between two stacked panels does not merge them`() {
        // Reproduces: page with Panel 1 (top) / white speech bubble in gutter / Panel 2 (bottom).
        // With two-sided contrast the speech bubble interior (luma ~250, background ~210) was
        // classified as content=1, blocking flood fill and causing both panels to merge into one
        // CC. With one-sided contrast (bg - v >= threshold) pixels brighter than the background
        // are non-content and flood fill flows through the bubble, keeping the panels separate.
        val W = 400
        val H = 560
        // Background luma ~210. Use a mid-tan byte so detectBackgroundLuma lands at 210.
        val BG: Byte = 210.toByte()
        // Panel content (dark ink borders) — clearly darker than BG (210-20=190 >> 32).
        val INK: Byte = 20.toByte()
        // Speech bubble interior — lighter than BG (250-210=40, two-sided hit, one-sided miss).
        val WHITE: Byte = 250.toByte()

        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = BG)
            // Panel 1: top half, solid dark fill
            canvas.rect(x = 20, y = 20, w = W - 40, h = 220, color = INK)
            // Gutter row (y=240..279): page background, plus a white speech bubble blob in the
            // middle. The bubble is 80px wide × 30px tall — interior is lighter than BG.
            canvas.rect(x = 160, y = 242, w = 80, h = 28, color = WHITE)
            // Panel 2: bottom half, solid dark fill
            canvas.rect(x = 20, y = 290, w = W - 40, h = 250, color = INK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)

        assertEquals(
            "speech bubble in gutter must not merge panels; expected 2 panels, got ${result.panels.size} (source=${result.source})",
            2, result.panels.size,
        )
        assertEquals(PanelSource.Auto, result.source)
    }

    @Test
    fun `2x2 grid with narrow horizontal gutter is split correctly via flood-fill`() {
        // Regression for the "rows merged into tall column strips" bug. The horizontal gutter
        // between rows 1 and 2 is 10px — below projectionMinBandThickness (15px). The projection
        // merges both rows into one tall row band, then detects two column bands within it,
        // producing two tall column strips (not 4 individual panels). The flood-fill split in
        // gridByProjection recovers the horizontal gutter: it's reachable from the page border
        // (~100% accessible), so internalGutterFloodFillFraction=30% is satisfied. The
        // internalGutterMaxFraction=25% guard does not fire: 10/(190+10+190) = 2.6% << 25%.
        val grid = fixture(width = 400, height = 420) { canvas ->
            canvas.fill(background = LIGHT)
            // 2×2 grid with 10px horizontal gutter (< 15px projection min) and 20px vertical gutter
            canvas.rect(x = 10, y = 10, w = 170, h = 190, color = DARK)   // top-left
            canvas.rect(x = 220, y = 10, w = 170, h = 190, color = DARK)  // top-right
            canvas.rect(x = 10, y = 210, w = 170, h = 200, color = DARK)  // bottom-left
            canvas.rect(x = 220, y = 210, w = 170, h = 200, color = DARK) // bottom-right
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 420)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "narrow horizontal gutter must not merge rows into column strips; expected 4 panels, got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            4, result.panels.size,
        )
        val topPanels = result.panels.filter { it.y < 200 }
        val bottomPanels = result.panels.filter { it.y >= 200 }
        assertEquals("expected 2 top panels", 2, topPanels.size)
        assertEquals("expected 2 bottom panels", 2, bottomPanels.size)
    }

    @Test
    fun `narrow side-strip false-vertical-split is suppressed by min-dimension guard`() {
        // Regression for the vertical-split branch of the caption-box guard (issue #751 symmetric
        // case). A wide panel has its left-side text strip (15px wide) flood-fill-reachable from
        // the top border via a gap in the strip's right border. splitSinglePanelRecursively finds
        // a vertical gutter at x≈15 (between strip and art) — but the resulting left sub-bbox
        // would be 15px wide, well below minPanelDimensionFraction × pageWidth (60px). Without
        // the guard the strip is split off, tightened to nothing, and filtered, leaving the panel
        // truncated on the left. With the guard the split is skipped and the panel is intact.
        val W = 400
        val H = 560
        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            // Main panel: solid dark fill. Left 15px is "caption strip" with LIGHT interior
            // (background colour) connected to the top page border, simulating a flood-fill-
            // reachable side strip. The strip is only 15px wide — 15/400 = 3.75%, well below
            // minPanelDimensionFraction (15%).
            canvas.rect(x = 15, y = 20, w = 365, h = 520, color = DARK)
            // The 15px left strip stays LIGHT (background fill), making those columns appear
            // flood-fill reachable from the top border — the column at x=15 would score high
            // gutter pixels and could trigger a vertical split without the guard.
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "narrow left strip must not be falsely split off; expected 1 panel, got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            1, result.panels.size,
        )
        assertTrue(
            "surviving panel must start near x=15, not after a false split; got x=${result.panels[0].x}",
            result.panels[0].x < 30,
        )
    }

    @Test
    fun `two side-by-side panels with enclosed vertical gutter are split via projection fallback`() {
        // Regression for issue #755: two panels share a gutter that is fully enclosed by panel
        // borders (the border ring is closed, so flood-fill from the page edge scores 0% for every
        // gutter column). The projection fallback in splitSinglePanelRecursively must still find the
        // gutter (< 10% content in a ≥ 7-column run) and split the merged CC bbox into two panels.
        //
        // Using solid-fill panels (not hollow) so that panel interiors have high content, keeping
        // only the actual gutter columns at near-zero content. Hollow panels would make the entire
        // interior + gutter a single low-content run wider than internalGutterMaxFraction.
        val W = 400
        val H = 560
        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            // Two solid-fill side-by-side panels. CC merges them into one bbox via the caps.
            canvas.rect(x = 10, y = 50, w = 160, h = 460, color = DARK)
            canvas.rect(x = 230, y = 50, w = 160, h = 460, color = DARK)
            // Horizontal caps that seal both ends of the gutter (x=170–229), preventing flood-fill
            // from the page top/bottom border from reaching the gutter columns at y=56–503.
            canvas.rect(x = 170, y = 50, w = 60, h = 6, color = DARK)
            canvas.rect(x = 170, y = 504, w = 60, h = 6, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)

        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "enclosed vertical gutter must be split via projection fallback; expected 2 panels, " +
                "got ${result.panels.map { "(${it.x},${it.y})${it.width}x${it.height}" }}",
            2, result.panels.size,
        )
        // Each panel should be roughly half the page width, not a single full-width bbox.
        for (p in result.panels) {
            assertTrue(
                "each panel width (${p.width}) must be < 70% of page width (no merged bbox)",
                p.width < W * 0.7,
            )
        }
    }

    @Test
    fun `mergeDiagonalSpanningPanels joins a column panel with the full-width band below it`() {
        // Regression for issue #784 (device-verified geometry of the real page, pageIndex 28):
        // the tall left character arrives as a row-1 COLUMN panel over an UNSPLIT full-width
        // row-2 band (projection could not find row 2's vertical gutter). The merge must produce
        // the tall panel (keeping the column's right edge) plus the band's right portion as the
        // row2-right panel.
        val pageW = 1042
        val pageH = 1482
        val bboxes = listOf(
            PanelDetector.Bbox(minX = 33, minY = 24, maxX = 591, maxY = 372),    // row1-left column (54%)
            PanelDetector.Bbox(minX = 483, minY = 24, maxX = 1013, maxY = 372),  // row1-right
            PanelDetector.Bbox(minX = 33, minY = 383, maxX = 1013, maxY = 726),  // row2 unsplit band (94%)
        )

        val merged = detector.mergeDiagonalSpanningPanels(bboxes, pageW, pageH)

        assertEquals("expected tall-left + row1-right + row2-right; got $merged", 3, merged.size)
        assertTrue(
            "tall-left must span rows 1-2 keeping the column's right edge (33,24)-(591,726); got $merged",
            merged.any { it.minX == 33 && it.minY == 24 && it.maxX == 591 && it.maxY == 726 },
        )
        assertTrue(
            "row2-right remainder (592,383)-(1013,726) must be emitted; got $merged",
            merged.any { it.minX == 592 && it.minY == 383 && it.maxX == 1013 && it.maxY == 726 },
        )
    }

    @Test
    fun `mergeDiagonalSpanningPanels never joins two stacked column panels`() {
        // Regression for issue #786 (device-verified geometry of the real page, pageIndex 26):
        // row3-left and row4-left are two clean stacked COLUMN panels whose right edges happen to
        // differ by 158px (> the 15% edge-diff threshold). The previous gate merged them and
        // emitted a 158px sliver as the row4 remainder — the exact panel the user reported.
        // A column panel below is a real standalone panel; only an UNSPLIT full-width band may
        // be merged upward.
        val pageW = 1042
        val pageH = 1484
        val bboxes = listOf(
            PanelDetector.Bbox(minX = 35, minY = 735, maxX = 392, maxY = 1066),  // row3-left (34%)
            PanelDetector.Bbox(minX = 26, minY = 1071, maxX = 550, maxY = 1439), // row4-left (50%)
            PanelDetector.Bbox(minX = 411, minY = 735, maxX = 1015, maxY = 1066), // row3-right
            PanelDetector.Bbox(minX = 558, minY = 1071, maxX = 1012, maxY = 1439), // row4-right
        )

        val merged = detector.mergeDiagonalSpanningPanels(bboxes, pageW, pageH)

        assertEquals(
            "two stacked column panels must never merge (bot is not a full-width band); got $merged",
            bboxes.toSet(), merged.toSet(),
        )
    }

    @Test
    fun `mergeDiagonalSpanningPanels never joins a full-width banner with the column below it`() {
        // A full-width banner over a column panel matches the same-left/edge-diff shape but must
        // never merge: the TOP must be a column panel (≤ 65% width). This pins the guard that
        // replaced the previous widthCap/topIsBanner checks.
        val pageW = 1000
        val pageH = 1400
        val bboxes = listOf(
            PanelDetector.Bbox(minX = 20, minY = 20, maxX = 910, maxY = 220),   // banner top (89%)
            PanelDetector.Bbox(minX = 20, minY = 240, maxX = 450, maxY = 700),  // column below
        )

        val merged = detector.mergeDiagonalSpanningPanels(bboxes, pageW, pageH)

        assertEquals(
            "banner + column must stay separate; got $merged",
            bboxes.toSet(), merged.toSet(),
        )
    }

    @Test
    fun `mergeDiagonalSpanningPanels full-width band threshold boundary`() {
        // The bot must span ≥ 85% of the page width to count as an unsplit band. Pin both sides
        // of the boundary so a future threshold tweak is a conscious decision: at 84% no merge,
        // at 86% merge + remainder.
        val pageW = 1000
        val pageH = 1400
        val top = PanelDetector.Bbox(minX = 10, minY = 20, maxX = 460, maxY = 350)
        val botBelow = PanelDetector.Bbox(minX = 10, minY = 370, maxX = 849, maxY = 700)   // 84.0%
        val botAbove = PanelDetector.Bbox(minX = 10, minY = 370, maxX = 869, maxY = 700)   // 86.0%

        val refused = detector.mergeDiagonalSpanningPanels(listOf(top, botBelow), pageW, pageH)
        assertEquals("84%-wide bot must not merge; got $refused", setOf(top, botBelow), refused.toSet())

        val merged = detector.mergeDiagonalSpanningPanels(listOf(top, botAbove), pageW, pageH)
        assertEquals("86%-wide bot must merge into tall + remainder; got $merged", 2, merged.size)
        assertTrue(
            "tall panel (10,20)-(460,700) expected; got $merged",
            merged.any { it.minX == 10 && it.minY == 20 && it.maxX == 460 && it.maxY == 700 },
        )
        assertTrue(
            "remainder (461,370)-(869,700) expected; got $merged",
            merged.any { it.minX == 461 && it.minY == 370 && it.maxX == 869 && it.maxY == 700 },
        )
    }

    // ------------------------------------------------------------------------------------------
    // Issue #787 — the merged remainder's LEFT edge must follow the diagonal boundary in the
    // band, not sit flatly at top.maxX + 1. These tests exercise diagonalRemainderStart through
    // mergeDiagonalSpanningPanels with a real mask + gutter (the run analysis needs pixels).
    // Geometry: column (30,20)-(560,350) over band (30,360)-(980,700) on a 1000×1400 page.
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the column-over-band mask for the #787 remainder tests. The band's interior gutter
     * occupies columns [upperGutter] in the band's upper half and [lowerGutter] in the lower
     * half; character content fills left of the gutter, right-panel content right of it. The
     * character also runs through the row-boundary gap (rows 351-359) inside the column's
     * x-range so boundaryContinuesThroughColumn passes, matching the real #784/#787 page.
     */
    private fun remainderMask(
        upperGutter: IntRange,
        lowerGutter: IntRange,
        boundaryContinues: Boolean = true,
    ): Pair<PanelDetector.CroppedMask, BooleanArray> {
        val w = 1000
        val h = 1400
        val data = ByteArray(w * h)
        fun fillContent(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) data[y * w + x] = 1
        }
        fillContent(30, 20, 560, 350) // top column panel
        if (boundaryContinues) {
            fillContent(30, 351, 544, 359) // character continues through the row boundary
        }
        val bandSplitY = 530
        fillContent(30, 360, upperGutter.first - 1, bandSplitY) // character, band upper half
        fillContent(upperGutter.last + 1, 360, 980, bandSplitY) // right panel, band upper half
        fillContent(30, bandSplitY + 1, lowerGutter.first - 1, 700) // character, band lower half
        fillContent(lowerGutter.last + 1, bandSplitY + 1, 980, 700) // right panel, band lower half
        val gutter = BooleanArray(w * h) { data[it] == 0.toByte() }
        return PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0) to gutter
    }

    @Test
    fun `merged remainder follows the diagonal gutter into the band`() {
        // Regression for issue #787: the band's gutter sits at 545..560 in the upper half and
        // 430..445 in the lower half (shift 115 = 11.5% of page width — a clear diagonal). The
        // remainder must start at the leftmost gutter run's right edge + 1 = 446, not at
        // top.maxX + 1 = 561 which chops off the remainder's lower-left corner.
        val (cropped, gutter) = remainderMask(upperGutter = 545..560, lowerGutter = 430..445)
        val top = PanelDetector.Bbox(minX = 30, minY = 20, maxX = 560, maxY = 350)
        val bot = PanelDetector.Bbox(minX = 30, minY = 360, maxX = 980, maxY = 700)

        val merged = detector.mergeDiagonalSpanningPanels(listOf(top, bot), 1000, 1400, cropped, gutter)

        assertEquals("expected tall + remainder; got $merged", 2, merged.size)
        assertTrue(
            "tall panel (30,20)-(560,700) expected; got $merged",
            merged.any { it.minX == 30 && it.minY == 20 && it.maxX == 560 && it.maxY == 700 },
        )
        assertTrue(
            "remainder must start at the diagonal gutter's right edge (446,360)-(980,700); got $merged",
            merged.any { it.minX == 446 && it.minY == 360 && it.maxX == 980 && it.maxY == 700 },
        )
    }

    @Test
    fun `merged remainder stays at the column edge when the band gutter is straight`() {
        // Both-sides boundary for the diagonal-shift floor: the gutter sits at 545..560 through
        // the whole band (shift 0 < 1.5% floor). No diagonal — the remainder must keep the
        // original top.maxX + 1 = 561 left edge.
        val (cropped, gutter) = remainderMask(upperGutter = 545..560, lowerGutter = 545..560)
        val top = PanelDetector.Bbox(minX = 30, minY = 20, maxX = 560, maxY = 350)
        val bot = PanelDetector.Bbox(minX = 30, minY = 360, maxX = 980, maxY = 700)

        val merged = detector.mergeDiagonalSpanningPanels(listOf(top, bot), 1000, 1400, cropped, gutter)

        assertEquals("expected tall + remainder; got $merged", 2, merged.size)
        assertTrue(
            "remainder must keep the straight-gutter left edge (561,360)-(980,700); got $merged",
            merged.any { it.minX == 561 && it.minY == 360 && it.maxX == 980 && it.maxY == 700 },
        )
    }

    @Test
    fun `merged remainder left edge is walked back to keep overlap under the sanity cap`() {
        // The remainder intentionally overlaps the tall panel in the diagonal transition zone,
        // but must never overlap so much that applyGlobalSanityChecks rejects the whole page
        // (that failure mode falls back to the CC path — the exact regression seen when this
        // fix was first attempted without the cap). Lower gutter at 395..410 puts the raw
        // remainder start at 411; the cap walks it back to 431 where overlap = 23.7% of the
        // remainder (just under overlapRejectFraction × 0.95).
        val (cropped, gutter) = remainderMask(upperGutter = 545..560, lowerGutter = 395..410)
        val top = PanelDetector.Bbox(minX = 30, minY = 20, maxX = 560, maxY = 350)
        val bot = PanelDetector.Bbox(minX = 30, minY = 360, maxX = 980, maxY = 700)

        val merged = detector.mergeDiagonalSpanningPanels(listOf(top, bot), 1000, 1400, cropped, gutter)

        assertEquals("expected tall + remainder; got $merged", 2, merged.size)
        val remainder = merged.single { it.minX != 30 }
        assertEquals("remainder left edge must be capped at 431; got $remainder", 431, remainder.minX)
        // Sanity: the capped overlap must be under the reject threshold.
        val overlap = (560 - remainder.minX + 1).toLong() * (700 - 360 + 1)
        val remainderArea = (980 - remainder.minX + 1).toLong() * (700 - 360 + 1)
        assertTrue(
            "overlap ${overlap * 100 / remainderArea}% must stay under 25% of the remainder",
            overlap * 100 < remainderArea * 25,
        )
    }

    @Test
    fun `column over band with a clean white gap never merges regardless of gap thickness`() {
        // boundaryContinuesThroughColumn must sample ONLY the gap rows: the bboxes' own edge
        // rows are content by construction, so including them made a thin genuine white gutter
        // (≤4 rows) read as "artwork continues" and merge two real panels. Here the 9-row gap
        // is pure white — no merge, whatever the geometry gate says.
        val (cropped, gutter) = remainderMask(
            upperGutter = 545..560,
            lowerGutter = 430..445,
            boundaryContinues = false,
        )
        val top = PanelDetector.Bbox(minX = 30, minY = 20, maxX = 560, maxY = 350)
        val bot = PanelDetector.Bbox(minX = 30, minY = 360, maxX = 980, maxY = 700)

        val merged = detector.mergeDiagonalSpanningPanels(listOf(top, bot), 1000, 1400, cropped, gutter)

        assertEquals(
            "white-gap column-over-band must stay two panels; got $merged",
            setOf(top, bot), merged.toSet(),
        )
    }

    @Test
    fun `merge result is independent of input bbox order`() {
        // mergeDiagonalSpanningPanels sorts by minY internally; passing the band before the
        // column must produce the same tall+remainder pair, not a double-emitted band.
        val (cropped, gutter) = remainderMask(upperGutter = 545..560, lowerGutter = 430..445)
        val top = PanelDetector.Bbox(minX = 30, minY = 20, maxX = 560, maxY = 350)
        val bot = PanelDetector.Bbox(minX = 30, minY = 360, maxX = 980, maxY = 700)

        val forward = detector.mergeDiagonalSpanningPanels(listOf(top, bot), 1000, 1400, cropped, gutter)
        val reversed = detector.mergeDiagonalSpanningPanels(listOf(bot, top), 1000, 1400, cropped, gutter)

        assertEquals("reversed input must merge identically; got $reversed vs $forward", forward.toSet(), reversed.toSet())
        assertEquals(2, reversed.size)
    }

    @Test
    fun `expandDiagonalBboxOverlaps caps the pad so overlap stays under the sanity threshold`() {
        // Both-sides boundary for the expand cap (the F1/F2 Fallback interaction): padding
        // doubles the pair's overlap, and on a narrow right panel the full overlapX/2 pad would
        // push overlap past overlapRejectFraction — applyGlobalSanityChecks would then reject
        // the whole page into Fallback. The pad must shrink until the pair stays legal.
        val left = PanelDetector.Bbox(minX = 0, minY = 0, maxX = 499, maxY = 299)
        val narrowRight = PanelDetector.Bbox(minX = 442, minY = 0, maxX = 861, maxY = 299)

        val capped = detector.expandDiagonalBboxOverlaps(listOf(left, narrowRight))
        val cLeft = capped.single { it.minX == 0 }
        val cRight = capped.single { it.minX != 0 }
        val overlap = (cLeft.maxX - cRight.minX + 1).toLong() * 300
        val smaller = minOf(cLeft.area(), cRight.area())
        assertTrue(
            "post-expand overlap must stay ≤ 25% of the smaller panel; got overlap=$overlap smaller=$smaller ($capped)",
            overlap * 100 <= smaller * 25,
        )
        assertTrue("pad must still expand the pair (cap ≠ no-op); got $capped", cLeft.maxX > 499 && cRight.minX < 442)

        // Wide right panel: the full pad (overlapX/2 = 29) stays under the cap and applies whole.
        val wideRight = PanelDetector.Bbox(minX = 442, minY = 0, maxX = 1441, maxY = 299)
        val uncapped = detector.expandDiagonalBboxOverlaps(listOf(left, wideRight))
        assertTrue(
            "wide pair must get the full ±29 pad; got $uncapped",
            uncapped.any { it.minX == 0 && it.maxX == 528 } && uncapped.any { it.minX == 413 && it.maxX == 1441 },
        )
    }

    // ------------------------------------------------------------------------------------------
    // repairDiagonalAdjacentColumnPairs — gap=0 diagonal profile scan (issue #795)
    // Geometry derived from the real page: left(27..487) + right(488..1013) on a 1042×1482 page.
    // The diagonal rises at ~6.6 rows/column from x=488 (y=739) to x=594 (y=1441).
    // ------------------------------------------------------------------------------------------

    /** CroppedMask + gutter for two touching (gap=0) panels on a 1042×1482 page. */
    private fun adjacentPairMask(
        leftMaxX: Int,
        rightMinX: Int,
        yMin: Int,
        yMax: Int,
        diagonalFromX: Int,
        diagonalToX: Int,
    ): Pair<PanelDetector.CroppedMask, BooleanArray> {
        val w = 1042
        val h = 1482
        val data = ByteArray(w * h) { 1 }  // all content; gutter array drives detection
        val gutter = BooleanArray(w * h)
        val diagFirstY = yMin
        val diagLastY = yMax
        val diagSpan = diagonalToX - diagonalFromX
        for (x in diagonalFromX..diagonalToX) {
            val y = diagFirstY + (diagLastY - diagFirstY) * (x - diagonalFromX) / diagSpan
            if (y in 0 until h) gutter[y * w + x] = true
        }
        return PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0) to gutter
    }

    @Test
    fun `repairDiagonalAdjacentColumnPairs widens touching panels with a gradual diagonal`() {
        // Regression for issue #795: gap=0 pair with a very gradual diagonal (0.15 rows/col)
        // was previously blocked because gap=0 was excluded from the repair loop. Now repaired
        // via diagonalProfileScan which detects the monotone-rising topmost-gutter profile.
        val w = 1042
        val h = 1482
        val left = PanelDetector.Bbox(minX = 27, minY = 739, maxX = 487, maxY = 1441)
        val right = PanelDetector.Bbox(minX = 488, minY = 739, maxX = 1013, maxY = 1441)
        val (cropped, gutter) = adjacentPairMask(
            leftMaxX = 487,
            rightMinX = 488,
            yMin = 739,
            yMax = 1441,
            diagonalFromX = 488,
            diagonalToX = 594,
        )

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        val repLeft = result.single { it.minX == left.minX }
        assertTrue(
            "left panel must be widened past x=${left.maxX} to cover the diagonal zone (old bug: unchanged); got $repLeft",
            repLeft.maxX > left.maxX,
        )
    }

    @Test
    fun `repairDiagonalAdjacentColumnPairs leaves touching panels unchanged when boundary is straight`() {
        // Safety boundary: gap=0 pair where the topmost-gutter profile is flat (no rise) must
        // NOT be widened — the profile scan's 65%-rising check rejects the straight boundary.
        val w = 1042
        val h = 1482
        val left = PanelDetector.Bbox(minX = 27, minY = 739, maxX = 487, maxY = 1441)
        val right = PanelDetector.Bbox(minX = 488, minY = 739, maxX = 1013, maxY = 1441)
        val data = ByteArray(w * h) { 1 }
        val gutter = BooleanArray(w * h)
        // Straight vertical boundary: a 3-column gutter at x=487..489 for the full union height.
        // The profile is flat (all y=739) → risingCount=0 → no repair.
        for (y in 739..1441) {
            for (x in 487..489) gutter[y * w + x] = true
        }
        val cropped = PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0)

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        assertEquals(
            "straight-boundary gap=0 panels must be unchanged; got $result",
            listOf(left, right), result,
        )
    }

    @Test
    fun `repairDiagonalAdjacentColumnPairs gap=0 diagonal height boundary — just above old 32 pct fires`() {
        // Boundary test for the height ceiling increase (0.32 → 0.55). A gap=0 pair with union
        // height 34 % (between old 0.32 and new 0.55) was blocked before and must now fire.
        val w = 1042
        val h = 1482
        val left = PanelDetector.Bbox(minX = 27, minY = 0, maxX = 487, maxY = 503)   // h=504, 34.0%
        val right = PanelDetector.Bbox(minX = 488, minY = 0, maxX = 1013, maxY = 503)
        val (cropped, gutter) = adjacentPairMask(
            leftMaxX = 487, rightMinX = 488, yMin = 0, yMax = 503,
            diagonalFromX = 488, diagonalToX = 560,
        )

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        val repLeft = result.single { it.minX == left.minX }
        assertTrue(
            "34%-height gap=0 pair must now fire (old bug: height > 0.32 blocked it); got $repLeft",
            repLeft.maxX > left.maxX,
        )
    }

    @Test
    fun `repairDiagonalAdjacentColumnPairs gap=0 diagonal height boundary — above new 55 pct blocked`() {
        // Boundary test: union height 56 % (above new 0.55 ceiling) must still not fire.
        val w = 1042
        val h = 1482
        val left = PanelDetector.Bbox(minX = 27, minY = 0, maxX = 487, maxY = 830)   // h=831, 56.1%
        val right = PanelDetector.Bbox(minX = 488, minY = 0, maxX = 1013, maxY = 830)
        val (cropped, gutter) = adjacentPairMask(
            leftMaxX = 487, rightMinX = 488, yMin = 0, yMax = 830,
            diagonalFromX = 488, diagonalToX = 590,
        )

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        assertEquals(
            "56%-height gap=0 pair must be blocked by height ceiling; got $result",
            listOf(left, right), result,
        )
    }

    @Test
    fun `profileB scan -- flat 1px-bump prefix does not inflate riseSpan when steep diagonal follows (issue 834)`() {
        // Boundary: falling-right diagonal detected via profileB when a horizontal gutter row
        // (between row 2 and row 3) generates flat-prefix profileB entries (dy=0) with 1-pixel
        // bumps (dy=1), followed by a steep diagonal rise (dy=8). The fix: when hasFlatPrefix is
        // true (dy=0 entries seen), require dy≥2 to anchor firstRisingIdx so the riseSpan excludes
        // the flat prefix's single-pixel bumps.
        //
        // Setup: profileB has flat entries (y=740, x=343..362, dy=0) with 4 bumps (dy=1 at
        // x=347,352,357,362), then a steep diagonal (dy=8/col, x=369..393).
        // Without the fix: firstRisingIdx=4 (first bump, dy=1), riseSpan large → ratio < 75% → FAIL.
        // With the fix:    hasFlatPrefix=true → minDy=2; firstRisingIdx set at first dy=8 → PASS.
        val w = 1042
        val h = 1482
        val data = ByteArray(w * h) { 1 }
        // Flat prefix at y=740 for x=343..362 (no gaps, no bumps).
        for (x in 343..362) data[740 * w + x] = 0
        // Bumps: restore y=740 to content, put gutter at y=741 for bump columns.
        for (bumpX in intArrayOf(347, 352, 357, 362)) {
            data[740 * w + bumpX] = 1
            data[741 * w + bumpX] = 0
        }
        // Diagonal x=369..393: first entry at y=740 (same level as flat), then rises at dy=8.
        data[740 * w + 369] = 0
        for (x in 370..393) data[(740 + (x - 369) * 8) * w + x] = 0
        val gutter = BooleanArray(w * h)
        val left = PanelDetector.Bbox(minX = 27, minY = 739, maxX = 363, maxY = 1441)
        val right = PanelDetector.Bbox(minX = 369, minY = 739, maxX = 1013, maxY = 1441)
        val cropped = PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0)

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        val repLeft = result.single { it.minX == left.minX }
        assertTrue(
            "falling-right diagonal with flat 1px-bump prefix must be detected and repaired; got $repLeft",
            repLeft.maxX > left.maxX,
        )
    }

    @Test
    fun `profileB scan -- clean steep diagonal without flat prefix still detected after dy threshold change`() {
        // Boundary safety: a clean falling-right diagonal (dy=8/col, no flat prefix) is detected
        // after the firstRisingIdx ≥ 2 fix. firstRisingIdx anchors at i=1 (dy=8 ≥ 2); riseSpan=41;
        // risingCount/riseSpan = 100% > 75%; extrapolated shift ≈ 96 < maxShiftProfile → PASS.
        val w = 1042
        val h = 1482
        val data = ByteArray(w * h) { 1 }
        for (x in 352..393) data[(740 + (x - 352) * 8) * w + x] = 0
        val gutter = BooleanArray(w * h)
        val left = PanelDetector.Bbox(minX = 27, minY = 739, maxX = 363, maxY = 1441)
        val right = PanelDetector.Bbox(minX = 364, minY = 739, maxX = 1013, maxY = 1441)
        val cropped = PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0)

        val result = detector.repairDiagonalAdjacentColumnPairs(listOf(left, right), cropped, gutter, w, h)

        val repLeft = result.single { it.minX == left.minX }
        assertTrue(
            "clean steep diagonal without flat prefix must be detected; got $repLeft",
            repLeft.maxX > left.maxX,
        )
    }

    @Test
    fun `diagonalGutterFallback fires when gutter rows qualify at 22pct but not 20pct — banner stub below`() {
        // Boundary test for the diagonalGutterFallback 22% threshold + bottomH < topH discriminator.
        // Uses 0/255 values to bypass the binarizer texture pass so gutter row content is preserved
        // exactly. Content = 0, background = 255.
        //
        // Page: 300×1000. Content column x=10..290 (281px, 93.7% of page width > 50% threshold).
        // Banner: y=10..609. Gutter: y=610..619 (10 rows). Stub: y=620..729.
        //
        // Gutter geometry:
        //   edgeMarginX = max(2, (281 * 0.1).toInt()) = 28 → innerMinX=28, innerMaxX=252
        //   maxRowContent (banner row inner range x=28..252) = 225 px
        //   20% threshold = 225 * 0.20 = 45 px
        //   22% threshold = 225 * 0.22 = 49.5 px
        //
        //   Left seal: x=10..83 DARK (74 px) → CROPPED x=0..73 → inner count x=28..73 = 46 px
        //   Right seal: x=290 DARK (CROPPED x=280, outside innerMaxX=252, contributes 0)
        //   Total inner count = 46 px: fails 20% check (46 ≥ 45), passes 22% check (46 < 49.5) ✓
        //
        // So regular banner projection fails; diagonalGutterFallback fires and sets
        // horizontalFromDiagonalFallback = true, which allows the bannerEligible bypass for
        // the (height*0.25) guard (bottomH=110 < 180).
        val binaryDark: Byte = 0
        val binaryLight: Byte = (-1).toByte()  // 255 as Byte
        val luma = ByteArray(300 * 1000) { binaryLight }
        for (y in 10..609) for (x in 10..290) luma[y * 300 + x] = binaryDark   // Banner
        for (y in 610..619) {
            for (x in 10..83) luma[y * 300 + x] = binaryDark  // left seal (46 inner-range px)
            luma[y * 300 + 290] = binaryDark                   // right seal (outside inner range)
        }
        for (y in 620..729) for (x in 10..290) luma[y * 300 + x] = binaryDark   // Stub
        val grid = PixelGrid(300, 1000, luma)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = grid.width, originalHeight = grid.height)
        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "diagonal fallback must split banner from stub; got ${result.panels}",
            2, result.panels.size,
        )
    }

    @Test
    fun `diagonalGutterFallback is blocked when stub is above and large content is below — bottomH greater than topH`() {
        // Boundary test: same gutter geometry (46 px inner content, fails 20% passes 22%) but the
        // short piece is at the TOP (topH≈100) and the large piece is at the BOTTOM (bottomH≈870).
        // The bottomH < topH guard must block the split — this is the compositional-gap case
        // (issue #756 pattern), not a banner.
        val binaryDark: Byte = 0
        val binaryLight: Byte = (-1).toByte()
        val luma = ByteArray(300 * 1000) { binaryLight }
        for (y in 10..109) for (x in 10..290) luma[y * 300 + x] = binaryDark   // Stub at top
        for (y in 110..119) {
            for (x in 10..83) luma[y * 300 + x] = binaryDark   // same 46-px inner-range left seal
            luma[y * 300 + 290] = binaryDark
        }
        for (y in 120..989) for (x in 10..290) luma[y * 300 + x] = binaryDark   // Large content below
        val grid = PixelGrid(300, 1000, luma)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = grid.width, originalHeight = grid.height)
        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "bottomH>topH guard must block the split; got ${result.panels}",
            1, result.panels.size,
        )
    }

    @Test
    fun `wide thin banner at 8pct of page height survives global sanity filter`() {
        // Boundary test for the bannerMinHeight threshold in applyGlobalSanityChecks.
        // A wide panel (≥ 50% of page width) at 8% of page height is just ABOVE the 7% floor and
        // must survive the filter as a real banner (#797/#802 fix raised the floor from 5% to 7%).
        // Page: 200w × 1000h. Banner: w=120 (60%), h=80 (8%). One large lower panel.
        val luma = ByteArray(200 * 1000) { LIGHT }
        for (y in 10..89) for (x in 10..129) luma[y * 200 + x] = DARK  // Banner: 120w × 80h
        // Gutter row 90..99
        for (y in 100..989) for (x in 10..189) luma[y * 200 + x] = DARK  // Lower panel
        val grid = PixelGrid(200, 1000, luma)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = grid.width, originalHeight = grid.height)
        assertEquals(PanelSource.Auto, result.source)
        assertEquals(
            "8%-height wide banner must survive the 7% filter; panels=${result.panels}",
            2, result.panels.size,
        )
    }

    @Test
    fun `wide thin sliver at 5pct of page height is filtered by global sanity check`() {
        // Boundary test for the bannerMinHeight threshold in applyGlobalSanityChecks.
        // A wide panel (≥ 50%) at 5% of page height is BELOW the 7% floor and must be filtered.
        // Before the #797/#802 fix this sliver would survive (old floor was 5%); after, it is dropped.
        // Page: 200w × 1000h. Sliver: w=120 (60%), h=50 (5%). One large panel above, one below.
        val luma = ByteArray(200 * 1000) { LIGHT }
        for (y in 10..289) for (x in 10..129) luma[y * 200 + x] = DARK  // Upper panel (28% tall)
        // Gutter row 290..299
        for (y in 300..349) for (x in 10..129) luma[y * 200 + x] = DARK  // Sliver: 120w × 50h (5%)
        // Gutter row 350..359
        for (y in 360..989) for (x in 10..189) luma[y * 200 + x] = DARK  // Lower panel (63% tall)
        val grid = PixelGrid(200, 1000, luma)
        val result = detector.detect(grid, pageIndex = 0, originalWidth = grid.width, originalHeight = grid.height)
        assertEquals(PanelSource.Auto, result.source)
        // The sliver (5%) is filtered; only the upper and lower panels survive.
        val sliver = result.panels.filter { p ->
            p.y + p.height / 2 in 300..360 && p.height < 70
        }
        assertEquals(
            "5%-height wide sliver must be filtered by the 7% banner floor; panels=${result.panels}",
            0, sliver.size,
        )
    }

    // ------------------------------------------------------------------------------------------
    // repairOneSidedRowJunctions — gap boundary tests (issue #814 fix: 12 → 15)
    // Directly exercise the repair function with crafted bboxes and a CroppedMask whose gap
    // strip has content on the LEFT half and gutter on the RIGHT half (one-sided pattern).
    // ------------------------------------------------------------------------------------------

    @Test
    fun `repairOneSidedRowJunctions gap=15 boundary — at new upper limit fires`() {
        // gap=15 is the new inclusive upper bound after the 12→15 bump (issue #814 fix).
        // The gap strip has content on the LEFT (x=0..299) and gutter on the RIGHT (x=300..599).
        // The repair must detect the one-sided pattern and emit 3 panels: spanning left column
        // + top-right piece + bottom-right piece.
        val (cropped, top, bottom) = oneSidedJunctionInputs(gap = 15)
        val repaired = detector.repairOneSidedRowJunctions(
            listOf(top, bottom), cropped,
            downscaledWidth = cropped.width, downscaledHeight = cropped.height,
        )
        assertEquals(
            "gap=15 is within new 0..15 window; repair must fire and emit 3 panels; got $repaired",
            3, repaired.size,
        )
    }

    @Test
    fun `repairOneSidedRowJunctions gap=16 boundary — one above limit does not fire`() {
        // gap=16 is just outside the 0..15 window; the repair must NOT fire.
        // Identical layout to the gap=15 test; only the vertical gap between the two bboxes
        // changes. The two full-width bboxes must remain unchanged (no spanning repair).
        val (cropped, top, bottom) = oneSidedJunctionInputs(gap = 16)
        val repaired = detector.repairOneSidedRowJunctions(
            listOf(top, bottom), cropped,
            downscaledWidth = cropped.width, downscaledHeight = cropped.height,
        )
        assertEquals(
            "gap=16 is outside 0..15 window; repair must not fire; inputs unchanged; got $repaired",
            2, repaired.size,
        )
        assertEquals(setOf(top, bottom), repaired.toSet())
    }

    // --- Energy-valley gutter confirmation (issue #788) ---

    @Test
    fun `energy valley confirmation splits bubble-blocked column gutter into 4 panels`() {
        // Reproduces issue #788 projection-path failure: row 1 has two panels separated by a
        // vertical gutter, but a speech bubble covers the MIDDLE portion of the gutter height,
        // pushing colContentCount above the 15% cutoff → the column gutter is never found →
        // row 1 is treated as a single full-width (suspicious) band.
        //
        // The bubble covers ~76% of the band height; the gutter is still visible (background)
        // in the top ~12% and bottom ~12% rows. Crucially, the visible fraction is in the
        // range [15%, 30%): gate 1 (energyValleyMinPartialGutterFraction=15%) PASSES, but the
        // flood-fill internal-gutter split (internalGutterFloodFillFraction=30%) CANNOT find it.
        // Only the energy valley can confirm the split:
        //   (1) partial gutter evidence: ~24% of rows at the gutter column are background → gate 1 passes.
        //   (2) energy valley: gutter columns have near-zero luma-gradient (solid-dark → flat)
        //       while dithered panel columns have high gradient → valley confirmed → split fires.
        // Without the energy-valley feature, neither the flood-fill nor any other stage finds the
        // gutter → suspicious band stays full-width → 3 panels instead of 4.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Row band 0 (y=20..269): clean two-panel layout, vertical gutter at x=190..209.
            canvas.ditheredRect(x = 20, y = 20, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 210, y = 20, w = 170, h = 250, base = DARK, accent = 160.toByte())
            // Row band 1 (y=290..539): same two panels; gutter visible at top+bottom but blocked
            // in the middle 190 rows by a "bubble" that crosses the gutter columns.
            canvas.ditheredRect(x = 20, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 210, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
            // Bubble blocking the gutter: solid-dark, covers y=320..509 (~76% of band height).
            // Leaves only ~24% gutter-background rows visible — above the 15% energy-valley gate
            // but below the 30% flood-fill threshold, so ONLY the energy valley can trigger the
            // split. Solid DARK has zero vertical gradient → luma energy ≈ 0 → valley fires.
            canvas.rect(x = 190, y = 320, w = 20, h = 190, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(
            "bubble-blocked gutter must be confirmed via energy-valley and split into 4 panels; " +
                "got ${result.panels.size} (source=${result.source})",
            4, result.panels.size,
        )
    }

    @Test
    fun `energy valley boundary — no partial gutter evidence suppresses split on full-width panel`() {
        // Both-sides boundary test for energyValleyMinPartialGutterFraction: a genuine full-width
        // panel (T-layout splash) must not be falsely split, because EVERY row at the candidate
        // column x has content — there is no partial gutter visible → the fraction-gate blocks it.
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Row band 0 (splash, full-width): genuine single panel across the full page.
            canvas.ditheredRect(x = 20, y = 20, w = 360, h = 250, base = DARK, accent = 160.toByte())
            // Row band 1 (two panels): provides a candidate column gutter at ≈x=200.
            canvas.ditheredRect(x = 20, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 210, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        // T-layout: splash row must remain a single full-width panel.
        assertEquals(
            "genuine full-width splash must NOT be split by energy-valley; expected 3 panels, " +
                "got ${result.panels.size} (source=${result.source})",
            3, result.panels.size,
        )
    }

    @Test
    fun `energy valley boundary — high-energy gutter columns suppress the split`() {
        // Both-sides boundary test for the valley depth ratio: when the gutter columns also have
        // high energy (dithered like the panels), no valley is found and the suspicious row stays
        // as a single full-width band → 3 panels total (2 from clean row + 1 full-width from row 1).
        val grid = fixture(width = 400, height = 560) { canvas ->
            canvas.fill(background = LIGHT)
            // Row band 0 (clean): two panels with gutter at x=190..209.
            canvas.ditheredRect(x = 20, y = 20, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 210, y = 20, w = 170, h = 250, base = DARK, accent = 160.toByte())
            // Row band 1: gutter partially visible (~24% of rows background, satisfying gate 1),
            // but below the 30% flood-fill threshold so only the energy valley decides.
            // Dithered gutter: high energy everywhere → no valley → gate 2 fails → no split.
            canvas.ditheredRect(x = 20, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 210, y = 290, w = 170, h = 250, base = DARK, accent = 160.toByte())
            canvas.ditheredRect(x = 190, y = 320, w = 20, h = 190, base = DARK, accent = 160.toByte())
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = 400, originalHeight = 560)

        assertEquals(
            "high-energy gutter columns must NOT be split by energy valley; expected 3 panels, " +
                "got ${result.panels.size} (source=${result.source})",
            3, result.panels.size,
        )
    }

    // --- Top-strip column-gap detection boundary tests (issues #848, #849) ---
    //
    // The top-strip block scans the top 25% of a wide (≥70%) bbox for vertical column gaps.
    // Fixture anatomy used by these tests:
    //   - 1-pixel dark header (y=0) seals the gap at the top, making it flood-fill-unreachable.
    //   - Left and right solid-dark panel columns (no white gap between them and the header).
    //   - White enclosed gap between the columns (x=leftW..rightStart-1, y=1..panelH).
    //   - Wide dark bottom panel (y=panelH+1..pageH-1) fills the gap's projection column so the
    //     standard projection/flood-fill gutter detection finds nothing → rawBestGutter=null.
    // This exactly reproduces the geometry that causes issues #848/#849 in the real detector.

    @Test
    fun `top-strip case A — horizontal split fires when gap ends before 70 pct of bbox height`() {
        // Fixture anatomy (with 20px white margin so binarizer correctly reads DARK=content):
        //   - 1px dark header at y=20 (x=20..979) seals the gap at the top so it is
        //     flood-fill-unreachable from the page border.
        //   - Left column (x=20..389) and right column (x=590..979) span y=20..479.
        //   - Wide bottom (x=20..979) spans y=480..979, connects both columns into one CC.
        //   - Enclosed gap: x=390..589, y=21..479.
        //
        // In the merged CC bbox the standard projection gutter fails (gap column has 50%+ content
        // because of the wide bottom) and flood-fill is blocked by the header → rawBestGutter=null.
        // Top-strip fires (width=960≥70%). gapEndFraction≈(479−20)/960=0.478<0.70 → case A →
        // horizontal split → TopBbox then gets case-B vertical split → 3 panels total.
        val grid = fixture(1000, 1000) { canvas ->
            canvas.fill(LIGHT)
            canvas.rect(20, 20, 960, 1, DARK)    // 1px header sealing the gap
            canvas.rect(20, 20, 370, 460, DARK)  // left column
            canvas.rect(590, 20, 390, 460, DARK) // right column
            canvas.rect(20, 480, 960, 500, DARK) // wide bottom
        }
        val result = detector.detect(grid, pageIndex = 0, originalWidth = 1000, originalHeight = 1000)
        assertEquals(
            "top-strip case A: expected Auto (3 panels), got source=${result.source} panels=${result.panels}",
            PanelSource.Auto, result.source,
        )
        assertTrue(
            "top-strip case A: expected ≥3 panels (left column, right column, wide bottom), " +
                "got ${result.panels.size}: ${result.panels}",
            result.panels.size >= 3,
        )
    }

    @Test
    fun `top-strip case A — split blocked when gap ends at or after 70 pct of bbox height`() {
        // Same enclosed-gap anatomy but the column panels span y=20..749 so the gap ends at y=749.
        // gapEndFraction=(749−20)/960=0.759≥0.70 → case A blocked.
        // The gap also doesn't reach the CC bbox bottom (residual=979−749=230>nearBottomTolerance)
        // so case B is blocked too. No top-strip split fires; the CC is returned as a single panel.
        val grid = fixture(1000, 1000) { canvas ->
            canvas.fill(LIGHT)
            canvas.rect(20, 20, 960, 1, DARK)    // 1px header
            canvas.rect(20, 20, 370, 730, DARK)  // left column (y=20..749)
            canvas.rect(590, 20, 390, 730, DARK) // right column (y=20..749)
            canvas.rect(20, 750, 960, 230, DARK) // wide bottom (y=750..979)
        }
        val result = detector.detect(grid, pageIndex = 0, originalWidth = 1000, originalHeight = 1000)
        assertEquals(
            "top-strip case A blocked at gapEndFraction≥0.70: expected 1 unsplit panel, " +
                "got ${result.panels.size}: ${result.panels}",
            1, result.panels.size,
        )
    }

    @Test
    fun `top-strip width guard — block does not fire for bbox narrower than 70 pct of page`() {
        // Narrow enclosed-gap CC: header (x=20..618, y=20), left sub-panel (x=20..299),
        // right sub-panel (x=400..618), gap (x=300..399, y=21..479), bottom connector (y=480..579).
        // CC width = 599 (60% of page) < 70% → top-strip block is skipped.
        // With rawBestGutter=null (enclosed gap fools projection and flood-fill) and top-strip
        // blocked by the width guard, the CC is returned as one merged panel instead of two.
        val grid = fixture(1000, 1000) { canvas ->
            canvas.fill(LIGHT)
            canvas.rect(20, 20, 599, 1, DARK)    // header (x=20..618, y=20)
            canvas.rect(20, 20, 280, 460, DARK)  // left sub-panel (x=20..299, y=20..479)
            canvas.rect(400, 20, 219, 460, DARK) // right sub-panel (x=400..618, y=20..479)
            canvas.rect(20, 480, 599, 100, DARK) // bottom connector (x=20..618, y=480..579)
        }
        val result = detector.detect(grid, pageIndex = 0, originalWidth = 1000, originalHeight = 1000)
        // The enclosed gap at x=300..399 fools standard gutter detection (gap column has
        // connector content below → 17% of column filled → just above 15% projection threshold).
        // The top-strip block is skipped because CC width=599<70%×1000. Result: 1 merged panel.
        assertEquals(
            "narrow CC: expected Auto (top-strip skipped, CC returned as one panel), " +
                "got source=${result.source}",
            PanelSource.Auto, result.source,
        )
        assertEquals(
            "narrow CC: expected 1 panel (top-strip width guard skips split), " +
                "got ${result.panels.size}: ${result.panels}",
            1, result.panels.size,
        )
    }

    @Test
    fun `narrow pillar group with no spanning panel — union returned unsplit when top portion is below min height`() {
        // Boundary test for the !hasSpanningPanel → splitUnionHorizontalOnly path in
        // coalesceNarrowStripColumns (#892). Three narrow right-column strips each have
        // h=139 which is just BELOW minDimPxH (1000 × 0.14 = 140). The union y-gutter at
        // y=139 produces a top portion of 139px < 140 — splitUnionHorizontalOnly returns the
        // whole union unsplit as one tall panel. Before the fix, the three strips were returned
        // separately and all filtered (too short/small individually).
        val W = 500
        val H = 1000
        val gutter = 17       // inter-row gutter height (LIGHT pixels)
        val stripH = 139      // each strip/row height — just below minDimPxH (140)
        val colGutter = 10    // column gutter width
        val leftW = 350       // left panels width (not narrow: 350/500 = 70% > 20%)
        val rightW = 90       // right strips width (narrow: 90/500 = 18% < 20%)
        val rightX = leftW + colGutter   // right strips start at x=360

        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            // Three left panels stacked (wide, serve as context)
            canvas.rect(x = 0, y = 0,                         w = leftW, h = stripH, color = DARK)
            canvas.rect(x = 0, y = stripH + gutter,           w = leftW, h = stripH, color = DARK)
            canvas.rect(x = 0, y = (stripH + gutter) * 2,     w = leftW, h = stripH, color = DARK)
            // Three narrow right-column strips at the same row structure
            canvas.rect(x = rightX, y = 0,                    w = rightW, h = stripH, color = DARK)
            canvas.rect(x = rightX, y = stripH + gutter,      w = rightW, h = stripH, color = DARK)
            canvas.rect(x = rightX, y = (stripH + gutter) * 2, w = rightW, h = stripH, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)
        assertEquals(PanelSource.Auto, result.source)

        // The 3 right strips have h=139 each, which is just below minDimPxH=140.  Without the
        // !hasSpanningPanel → splitUnionHorizontalOnly fix, each strip is returned separately and
        // all 3 are filtered by applyGlobalSanityChecks (h/H = 13.9% < 14%).  With the fix they
        // are coalesced into a union that survives sanity checks.
        val rightPanels = result.panels.filter { it.x > W / 2 }
        assertTrue(
            "right-column strips (h=$stripH < minDimPxH=140 each) must be coalesced into at " +
                "least 1 surviving panel; pre-fix: all 3 filtered individually; " +
                "got panels=${result.panels}",
            rightPanels.isNotEmpty(),
        )
        assertTrue(
            "all right-column panels must have h ≥ minDimPxH=140; " +
                "got heights=${rightPanels.map { it.height }}",
            rightPanels.all { it.height >= 140 },
        )
    }

    @Test
    fun `narrow pillar group with no spanning panel — union split when both portions meet min height`() {
        // Boundary test for the !hasSpanningPanel → splitUnionHorizontalOnly path in
        // coalesceNarrowStripColumns. Two narrow right-column strips each have h ≥ minDimPxH
        // (1000 × 0.14 = 140). The union y-gutter at y=200 produces a top portion of 200px ≥ 140
        // AND a bottom portion of 463px ≥ 140 — splitUnionHorizontalOnly splits the union into
        // two distinct panels, one for each original strip.
        val W = 500
        val H = 1000
        val gutter = 17
        val strip1H = 200      // top strip height — above minDimPxH (140)
        val strip2H = 463      // bottom strip height — above minDimPxH (140)
        val colGutter = 10
        val leftW = 350
        val rightW = 90
        val rightX = leftW + colGutter

        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            canvas.rect(x = 0, y = 0,                   w = leftW, h = strip1H, color = DARK)
            canvas.rect(x = 0, y = strip1H + gutter,    w = leftW, h = strip2H, color = DARK)
            canvas.rect(x = rightX, y = 0,              w = rightW, h = strip1H, color = DARK)
            canvas.rect(x = rightX, y = strip1H + gutter, w = rightW, h = strip2H, color = DARK)
        }

        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)
        assertEquals(PanelSource.Auto, result.source)

        // Both strips are individually ≥ minDimPxH=140, so even when coalesced into a union,
        // splitUnionHorizontalOnly splits them back apart (top portion h=200 ≥ 140, bottom h=463 ≥ 140).
        // Verify the right column is represented by exactly 2 separate panels — not merged into 1.
        val rightPanels = result.panels.filter { it.x > W / 2 }
        assertEquals(
            "right-column strips (h=$strip1H and h=$strip2H, both ≥ minDimPxH=140) must be " +
                "detected as 2 separate panels; got panels=${result.panels}",
            2, rightPanels.size,
        )
    }

    /**
     * Builds a [PanelDetector.CroppedMask] and two full-width [PanelDetector.Bbox]es whose
     * vertical separation is exactly [gap] pixels. The gap strip has content on the left half
     * (x=0..299) and gutter on the right half (x=300..599) — the one-sided pattern that
     * [PanelDetector.repairOneSidedRowJunctions] is designed to detect.
     *
     * Page size: 600×800. Top bbox: y=0..374. Gap: y=375..374+gap. Bottom bbox: y=375+gap..799.
     */
    private fun oneSidedJunctionInputs(gap: Int): Triple<PanelDetector.CroppedMask, PanelDetector.Bbox, PanelDetector.Bbox> {
        val w = 600
        val h = 800
        val topEnd = 374
        val gapStart = topEnd + 1
        val gapEnd = gapStart + gap - 1
        val botStart = gapEnd + 1

        val data = ByteArray(w * h)
        // Top panel: full content.
        for (y in 0..topEnd) for (x in 0 until w) data[y * w + x] = 1
        // Gap: left half = content, right half = gutter (0 = default).
        for (y in gapStart..gapEnd) for (x in 0 until 300) data[y * w + x] = 1
        // Bottom panel: full content.
        for (y in botStart until h) for (x in 0 until w) data[y * w + x] = 1

        val cropped = PanelDetector.CroppedMask(w, h, data, offsetX = 0, offsetY = 0)
        val top = PanelDetector.Bbox(minX = 0, minY = 0, maxX = w - 1, maxY = topEnd)
        val bottom = PanelDetector.Bbox(minX = 0, minY = botStart, maxX = w - 1, maxY = h - 1)
        return Triple(cropped, top, bottom)
    }

    // --- Synthetic fixture builders ---

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

    @Test
    fun `narrow strips from the same column (x-centres within 25pct) are coalesced into a tall panel`() {
        // Boundary test for the x-centre span guard in coalesceNarrowStripColumns
        // (!hasSpanningPanel path). When strips' x-centres span ≤ 25% of the downscaled page
        // width they are in the same column and the union-fallback applies.
        // Layout: wide left panels + two narrow right strips at the same x (x-centre span = 0).
        val W = 400; val H = 600
        val leftW = 290; val rightW = 70   // rightW/W = 17.5% < 20% → narrow
        val rightX = leftW + 10
        val strip1H = 120; val strip2H = 120; val gutter = 20
        // x-centre of both strips = rightX + rightW/2 = 335; span = 0 → ≤ 25% of W/2 = 50px
        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            canvas.rect(x = 0, y = 0, w = leftW, h = strip1H, color = DARK)
            canvas.rect(x = 0, y = strip1H + gutter, w = leftW, h = strip2H, color = DARK)
            canvas.rect(x = rightX, y = 0, w = rightW, h = strip1H, color = DARK)
            canvas.rect(x = rightX, y = strip1H + gutter, w = rightW, h = strip2H, color = DARK)
        }
        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)
        assertEquals(PanelSource.Auto, result.source)
        val rightPanels = result.panels.filter { it.x > W / 2 }
        assertTrue(
            "same-column narrow strips (x-centre span=0 ≤ 25% guard) must produce at least one " +
                "surviving right-column panel; got=${result.panels}",
            rightPanels.isNotEmpty(),
        )
    }

    @Test
    fun `narrow strips from different columns (x-centres span more than 25pct) are NOT coalesced`() {
        // Boundary test: strips whose x-centres span > 25% of the downscaled page width come from
        // different columns and must be kept separate. Coalescing them loses real panels.
        // Layout: two wide row panels (top and bottom) plus a narrow left and a narrow right strip
        // in the middle row. The wide rows provide panel coverage for Auto; the narrow strips are
        // genuinely separate panels that must not be merged into a single wide union.
        // x-centres: leftStrip at 10+30=40, rightStrip at 330+30=370; span=330 >> 25% of W/2=50.
        val W = 400; val H = 800
        val stripW = 60    // 60/400 = 15% < 20% → narrow
        val leftX = 10; val rightX = 330
        val rowH = 200; val stripH = 200; val gutter = 20
        val grid = fixture(width = W, height = H) { canvas ->
            canvas.fill(background = LIGHT)
            // Wide top and bottom rows to ensure sufficient panel coverage for Auto detection.
            canvas.rect(x = 5, y = 0, w = W - 10, h = rowH, color = DARK)
            canvas.rect(x = 5, y = rowH + stripH + gutter * 2, w = W - 10, h = rowH, color = DARK)
            // Two narrow strips in the middle at very different x positions (different columns).
            canvas.rect(x = leftX, y = rowH + gutter, w = stripW, h = stripH, color = DARK)
            canvas.rect(x = rightX, y = rowH + gutter, w = stripW, h = stripH, color = DARK)
        }
        val result = detector.detect(grid, pageIndex = 0, originalWidth = W, originalHeight = H)
        assertEquals(PanelSource.Auto, result.source)
        // Both narrow strips must survive as separate panels — not merged into one wide union.
        val midY = rowH + gutter + stripH / 2
        val leftPanels = result.panels.filter { it.x + it.width / 2 < W / 2 && it.y + it.height / 2 in (rowH)..(rowH + stripH + gutter) }
        val rightPanels = result.panels.filter { it.x + it.width / 2 >= W / 2 && it.y + it.height / 2 in (rowH)..(rowH + stripH + gutter) }
        assertTrue(
            "left-column narrow strip must be kept separate (x-centre span > 25% guard); " +
                "got=${result.panels}",
            leftPanels.isNotEmpty(),
        )
        assertTrue(
            "right-column narrow strip must be kept separate (x-centre span > 25% guard); " +
                "got=${result.panels}",
            rightPanels.isNotEmpty(),
        )
    }

    // --- mergeSharedBorderFalseGaps boundary tests ---

    @Test
    fun `mergeSharedBorderFalseGaps merges two same-row panels when at least two panels below straddle the gap`() {
        // Boundary test — inside the trigger zone:
        // A thin ink border (binarized to white) creates a gap of ~4% page width between two
        // same-row panels. The pair IS the topmost on the page (gate 1). No other row has a
        // column boundary near gapLeft (gate 2 passes). Two wide panels below span the full gap
        // [gapLeft, gapRight] confirming the gap is a binarization artifact (gate 3). Merge.
        val pageW = 2000
        val p0 = PanelRegion(x = 100, y = 100, width = 700, height = 400)   // top-row left; right=800 (gapLeft)
        val p1 = PanelRegion(x = 880, y = 100, width = 620, height = 400)   // top-row right; gapRight=880, gapW=80
        val p2 = PanelRegion(x = 100, y = 500, width = 1400, height = 400)  // row 2 below: x≤800, x+w=1500≥880, w=1400≥mergedW/2=700
        val p3 = PanelRegion(x = 100, y = 900, width = 1400, height = 400)  // row 3 below: same
        val panels = listOf(p0, p1, p2, p3)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        // Gates: (1) topmost pair ✓; (2) no validator ✓; (3) count=2 ≥ 2 ✓ → merge
        assertEquals("expected 3 panels after merge (merged top-row pair + rows 2-3); got=$result", 3, result.size)
        val topPanel = result.firstOrNull { it.y + it.height / 2 < 500 }
        assertNotNull("expected one merged top-row panel; got=$result", topPanel)
        assertEquals("merged top panel must span from x=100; got=$result", 100, topPanel!!.x)
        assertEquals("merged top panel width must be 1400; got=$result", 1400, topPanel.width)
    }

    @Test
    fun `mergeSharedBorderFalseGaps does NOT merge when only one panel below spans the full gap`() {
        // Boundary test — just outside gate 3:
        // Topmost pair with narrow gap; gate 1 (topmost) and gate 2 (no column validator) both
        // pass, but only ONE panel below spans [gapLeft, gapRight]. Gate 3 requires ≥ 2.
        val pageW = 2000
        val p0 = PanelRegion(x = 100, y = 100, width = 700, height = 400)   // top-row left
        val p1 = PanelRegion(x = 880, y = 100, width = 620, height = 400)   // top-row right (gapW=80)
        val p2 = PanelRegion(x = 100, y = 500, width = 1400, height = 400)  // only 1 panel below spanning
        val panels = listOf(p0, p1, p2)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        // No merge: only 1 straddling panel below (need ≥ 2 for gate 3)
        assertEquals("panels must NOT be merged when < 2 panels span the gap below; got=$result", 3, result.size)
    }

    @Test
    fun `mergeSharedBorderFalseGaps does NOT merge non-topmost pair even when two rows below span the gap`() {
        // Boundary test — gate 1 blocks: the pair is NOT in the topmost row.
        // A panel row ABOVE the candidate pair means any straddle-below evidence is ambiguous
        // (the rows below could simply be wider than this row's genuine column split). Only
        // true top-row artifact gaps can be merged.
        val pageW = 2000
        val pAbove = PanelRegion(x = 100, y = 0, width = 1400, height = 90)   // a panel ABOVE the candidate pair
        val p0 = PanelRegion(x = 100, y = 100, width = 700, height = 400)     // middle-left (not topmost)
        val p1 = PanelRegion(x = 880, y = 100, width = 620, height = 400)     // middle-right
        val p2 = PanelRegion(x = 100, y = 500, width = 1400, height = 400)    // row below — spans full gap
        val p3 = PanelRegion(x = 100, y = 900, width = 1400, height = 400)    // row below — spans full gap
        val panels = listOf(pAbove, p0, p1, p2, p3)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        // No merge: gate 1 fails (pAbove starts at y=0 < candidateTop=100)
        assertEquals("must NOT merge a non-topmost pair even with 2 straddling rows below; got=$result", 5, result.size)
    }

    @Test
    fun `mergeSharedBorderFalseGaps does NOT merge when adjacent row has a column boundary at the gap`() {
        // Boundary test — gate 2 blocks: an adjacent row (within 2×shorter of candidateBottom)
        // has a panel right edge near gapLeft.
        // shorter=400, candidateBottom=500, adjacentZoneBottom=500+800=1300.
        // colTolerance = max(80, 100) = 100. p2 right=810; |810-800|=10 ≤ 100 → VALIDATOR.
        val pageW = 2000
        val p0 = PanelRegion(x = 100, y = 100, width = 700, height = 400)  // top-left; right=800 (gapLeft)
        val p1 = PanelRegion(x = 880, y = 100, width = 620, height = 400)  // top-right; gapW=80
        val p2 = PanelRegion(x = 100, y = 600, width = 710, height = 400)  // mid-left; y=600 < adjacentZoneBottom=1300; right=810; VALIDATOR
        val p3 = PanelRegion(x = 880, y = 600, width = 620, height = 400)  // mid-right (same column split)
        val p4 = PanelRegion(x = 100, y = 1100, width = 1400, height = 400) // below — spans full gap; y=1100 < 1300 (in zone)
        val p5 = PanelRegion(x = 100, y = 1600, width = 1400, height = 400) // below — y=1600 > 1300 (outside zone, not a validator)
        val panels = listOf(p0, p1, p2, p3, p4, p5)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        // No merge: gate 2 fires (p2 is an adjacent-row column validator)
        assertEquals("must NOT merge when adjacent row has a column boundary near gap_left; got=$result", 6, result.size)
    }

    @Test
    fun `mergeSharedBorderFalseGaps does NOT merge when gap exceeds 7pct of page width`() {
        // Boundary test — gap just outside the 7% trigger zone (8%):
        // A column gap of 8% of page width (160px on a 2000px-wide page) is wider than the
        // binarizer-artifact threshold — it is treated as a real inter-column gutter.
        val pageW = 2000
        val gapW = (pageW * 0.08).toInt()  // 160px — just outside the 7% trigger
        val p0 = PanelRegion(x = 100, y = 100, width = 600, height = 400)
        val p1 = PanelRegion(x = 100 + 600 + gapW, y = 100, width = 700, height = 400) // gap=160px
        val mergedW = p1.x + p1.width - p0.x  // 1400 + 160 = ... actually 100+600+160+700=1560? no
        // p0 right=700, p1 left=860, gap=160, p1 right=860+700=1560, merged=1560-100=1460
        val p2 = PanelRegion(x = 100, y = 600, width = 1460, height = 400) // matches merged width exactly
        val panels = listOf(p0, p1, p2)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        assertEquals("panels must NOT be merged when gap > 7% of page width; got=$result", 3, result.size)
    }

    @Test
    fun `mergeSharedBorderFalseGaps does NOT merge tall column panel with short row panel`() {
        // The height-ratio guard (max/min < 2) prevents merging a short top-row panel with a tall
        // right-column panel even though they share the same y-start and have a narrow gap.
        val pageW = 2000
        val shortPanel = PanelRegion(x = 100, y = 100, width = 1400, height = 400) // h=400
        val tallPanel = PanelRegion(x = 1560, y = 100, width = 400, height = 1300) // h=1300, gap=60px
        // height ratio = 1300/400 = 3.25 > 2 → must NOT merge
        val p2 = PanelRegion(x = 100, y = 600, width = 1860, height = 400) // wide other panel
        val panels = listOf(shortPanel, tallPanel, p2)
        val result = detector.mergeSharedBorderFalseGaps(panels, pageW)
        assertEquals(
            "tall column panel must not merge with short row panel (height ratio > 2); got=$result",
            3, result.size,
        )
    }
}
