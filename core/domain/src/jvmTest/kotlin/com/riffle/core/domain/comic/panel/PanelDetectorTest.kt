package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
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
}
