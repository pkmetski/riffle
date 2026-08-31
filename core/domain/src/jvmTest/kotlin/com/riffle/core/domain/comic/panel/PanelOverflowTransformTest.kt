package com.riffle.core.domain.comic.panel

import com.riffle.core.domain.comic.PanelOverflowBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelOverflowTransformTest {

    // Viewport: 1080×1920 (portrait 9:16). Image: 1080×1500.
    // fitScale = min(1080/1080, 1920/1500) = 1.0
    private val vpW = 1080
    private val vpH = 1920
    private val imgW = 1080
    private val imgH = 1500

    // Wide panel: spans full width, short height (a banner row at the top).
    // width = 1080 (100% of imgW). displayW = 1080 < vpH 1920 → overflowing.
    private val widePanelBanner = PanelRegion(x = 0, y = 0, width = 1080, height = 300)

    // Wide panel at 89% — above the 85% threshold. Overflowing.
    private val almostWidePanelBanner = PanelRegion(x = 60, y = 0, width = 960, height = 300)

    // Wide panel at 84% — just below the 85% threshold. Not overflowing.
    private val belowThresholdPanel = PanelRegion(x = 86, y = 0, width = 908, height = 300)

    // Normal panel: 50% width. Not overflowing.
    private val normalPanel = PanelRegion(x = 0, y = 300, width = 540, height = 600)

    // Landscape viewport: 1920×1080. Same image.
    // fitScale = min(1920/1080, 1080/1500) = 0.72
    // Tall panel: full height (1500px), narrow. displayH = 1500*0.72=1080 = vpW 1920? No.
    // Let's use: tall panel spans 95% of imgH = 1425px. displayH = 1425*0.72 = 1026 < vpW 1920 → overflowing.
    private val tallPanel = PanelRegion(x = 0, y = 0, width = 300, height = 1425)

    @Test
    fun isOverflowingReturnsTrueForFullWidthPortraitPanel() {
        assertTrue(PanelOverflowTransform.isOverflowing(widePanelBanner, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsTrueForPanelAt89PercentWidth() {
        // 89% > 85% threshold → overflowing (threshold lowered from 0.9 to 0.85).
        assertTrue(PanelOverflowTransform.isOverflowing(almostWidePanelBanner, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsFalseForPanelBelow85PercentWidthThreshold() {
        assertFalse(PanelOverflowTransform.isOverflowing(belowThresholdPanel, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsFalseForNormalPanel() {
        assertFalse(PanelOverflowTransform.isOverflowing(normalPanel, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsTrueForFullHeightLandscapePanel() {
        // Landscape: vpW=1920, vpH=1080
        assertTrue(PanelOverflowTransform.isOverflowing(tallPanel, imgW, imgH, 1920, 1080))
    }

    @Test
    fun isOverflowingReturnsFalseForFullWidthPanelAlreadyInLandscape() {
        // In landscape (vpW=1920 > vpH=1080), a wide panel's displayW≈1920 is NOT < vpH=1080.
        assertFalse(PanelOverflowTransform.isOverflowing(widePanelBanner, imgW, imgH, 1920, 1080))
    }

    @Test
    fun applyOverflowOffReturnsPanelsUnchanged() {
        val panels = listOf(widePanelBanner, normalPanel)
        val result = PanelOverflowTransform.applyOverflow(panels, imgW, imgH, vpW, vpH, PanelOverflowBehavior.OFF)
        assertEquals(panels, result)
    }

    @Test
    fun applyOverflowSplitsSplitsWidePanelAtHorizontalMidpoint() {
        val result = PanelOverflowTransform.applyOverflow(
            listOf(widePanelBanner), imgW, imgH, vpW, vpH, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(2, result.size)
        val left = result[0]
        val right = result[1]
        assertEquals(widePanelBanner.x, left.x)
        assertEquals(widePanelBanner.y, left.y)
        assertEquals(540, left.width)
        assertEquals(widePanelBanner.height, left.height)
        assertEquals(540, right.x)
        assertEquals(widePanelBanner.y, right.y)
        assertEquals(540, right.width)
        assertEquals(widePanelBanner.height, right.height)
    }

    @Test
    fun applyOverflowSplitLeavesNonOverflowingPanelsUnchanged() {
        val result = PanelOverflowTransform.applyOverflow(
            listOf(normalPanel), imgW, imgH, vpW, vpH, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(listOf(normalPanel), result)
    }

    @Test
    fun applyOverflowSmartSplitWithNoSamplerFallsToCenterSplit() {
        // SMART_SPLIT + null sampler must give the same result as SPLIT (dead-centre fallback).
        val panels = listOf(widePanelBanner, normalPanel)
        val smart = PanelOverflowTransform.applyOverflow(
            panels, imgW, imgH, vpW, vpH, PanelOverflowBehavior.SMART_SPLIT, null,
        )
        val center = PanelOverflowTransform.applyOverflow(
            panels, imgW, imgH, vpW, vpH, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(center, smart)
    }

    @Test
    fun applyOverflowSmartSplitSplitsAtLowestEnergySeam() {
        // widePanelBanner: PanelRegion(0, 0, 1080, 300) with imgW=1080.
        // Synthetic sampler returns zero energy at index 400 out of 1080,
        // which is in the middle third [360, 720). Split must land at x=400.
        val targetCol = 400
        val sampler: (PanelRegion, Boolean) -> FloatArray = { panel, _ ->
            FloatArray(panel.width) { col -> if (col == targetCol) 0f else 1_000f }
        }
        val result = PanelOverflowTransform.applyOverflow(
            listOf(widePanelBanner), imgW, imgH, vpW, vpH,
            PanelOverflowBehavior.SMART_SPLIT, sampler,
        )
        assertEquals(2, result.size)
        assertEquals(0, result[0].x)
        assertEquals(targetCol, result[0].width)
        assertEquals(targetCol, result[1].x)
        assertEquals(imgW - targetCol, result[1].width)
    }

    @Test
    fun applyOverflowSmartSplitIgnoresEnergyOutsideMiddleThird() {
        // Low-energy column at index 50 (outside middle third [360..720) for width=1080).
        // The seam finder must ignore it and pick the best seam within the middle third.
        val outsideCol = 50
        val insideCol = 500  // inside middle third, second lowest energy
        val sampler: (PanelRegion, Boolean) -> FloatArray = { panel, _ ->
            FloatArray(panel.width) { col ->
                when (col) {
                    outsideCol -> 0f       // lowest energy overall, but outside middle third
                    insideCol -> 1f        // lowest inside middle third — must win
                    else -> 1_000f
                }
            }
        }
        val result = PanelOverflowTransform.applyOverflow(
            listOf(widePanelBanner), imgW, imgH, vpW, vpH,
            PanelOverflowBehavior.SMART_SPLIT, sampler,
        )
        assertEquals(2, result.size)
        assertEquals(insideCol, result[0].width)
    }

    @Test
    fun applyOverflowSplitSplitsMixedPageCorrectly() {
        val panels = listOf(widePanelBanner, normalPanel)
        val result = PanelOverflowTransform.applyOverflow(panels, imgW, imgH, vpW, vpH, PanelOverflowBehavior.SPLIT)
        // widePanelBanner splits into 2, normalPanel stays as 1 → total 3
        assertEquals(3, result.size)
    }

    // --- Splash-page regression (never split without real zoom gain) ---
    //
    // A full-page splash panel must never be split top/bottom: both slices stay width-bound at
    // the same zoom as the whole panel, so the "split" is the same fit view merely panned
    // vertically with black voids — the senseless pan reported on single-panel pages. Whether
    // it splits LEFT/RIGHT depends on the viewport: on a tall 20:9 phone each half genuinely
    // magnifies (gain ≈ 1.57 ≥ 1.5), on a 16:9 display it doesn't (gain ≈ 1.25 < 1.5).

    // Full-page splash: 95% width, 97% height of the image.
    private val splashPanel = PanelRegion(x = 27, y = 22, width = 1026, height = 1455)

    @Test
    fun isOverflowingReturnsFalseForFullPageSplashPanelOn16To9Viewport() {
        assertFalse(PanelOverflowTransform.isOverflowing(splashPanel, imgW, imgH, vpW, vpH))
    }

    @Test
    fun applyOverflowLeavesFullPageSplashPanelUnsplitOn16To9Viewport() {
        val split = PanelOverflowTransform.applyOverflow(
            listOf(splashPanel), imgW, imgH, vpW, vpH, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(listOf(splashPanel), split)
        val smart = PanelOverflowTransform.applyOverflow(
            listOf(splashPanel), imgW, imgH, vpW, vpH, PanelOverflowBehavior.SMART_SPLIT,
            energySampler = { panel, _ -> FloatArray(panel.width) { 1f } },
        )
        assertEquals(listOf(splashPanel), smart)
    }

    @Test
    fun isOverflowingReturnsTrueForFullPageSplashPanelOnTall20To9Viewport() {
        // vp 1080×2400: whole-panel zoom = 1080/1026 ≈ 1.05 (width-bound); left/right half
        // zoom = 2400/1455 ≈ 1.65 → gain ≈ 1.57 ≥ 1.5 → splitting genuinely magnifies.
        assertTrue(PanelOverflowTransform.isOverflowing(splashPanel, imgW, imgH, 1080, 2400))
    }

    @Test
    fun applyOverflowSplitsSplashPanelLeftRightOnTall20To9Viewport() {
        // The split direction must be the max-gain axis (horizontal), never top/bottom —
        // a top/bottom split of a splash panel has zero zoom gain (the senseless pan).
        val result = PanelOverflowTransform.applyOverflow(
            listOf(splashPanel), imgW, imgH, 1080, 2400, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(2, result.size)
        val left = result[0]
        val right = result[1]
        assertEquals(splashPanel.x, left.x)
        assertEquals(splashPanel.y, left.y)
        assertEquals(513, left.width)
        assertEquals(splashPanel.height, left.height)
        assertEquals(splashPanel.x + 513, right.x)
        assertEquals(splashPanel.y, right.y)
        assertEquals(513, right.width)
        assertEquals(splashPanel.height, right.height)
    }

    @Test
    fun smartSplitRequestsColumnEnergiesForSplashPanelOnTallViewport() {
        // The sampler must be asked for the transform's max-gain axis (columns for a
        // horizontal split), not the panel's own aspect (which would pick rows here).
        var requestedHorizontal: Boolean? = null
        val result = PanelOverflowTransform.applyOverflow(
            listOf(splashPanel), imgW, imgH, 1080, 2400, PanelOverflowBehavior.SMART_SPLIT,
            energySampler = { panel, splitHorizontally ->
                requestedHorizontal = splitHorizontally
                FloatArray(panel.width) { 1f }
            },
        )
        assertEquals(true, requestedHorizontal)
        assertEquals(2, result.size)
        // Uniform energies → seam at the middle-third minimum scan start… whichever index wins,
        // both halves must be non-empty vertical slices of the full panel height.
        assertTrue(result.all { it.height == splashPanel.height })
        assertEquals(splashPanel.width, result[0].width + result[1].width)
    }

    // --- Zoom-gain boundary (MIN_SPLIT_ZOOM_GAIN = 1.5) ---
    //
    // Wide panels at full image width; zoom-whole is width-bound at 1.0, and a half-split's
    // zoom is min(2.0, vpH / panelDisplayH). Gain crosses 1.5 exactly when the displayed panel
    // height is vpH / 1.5 = 1280: taller → gain < 1.5 (no split), shorter → gain > 1.5 (split).

    @Test
    fun isOverflowingReturnsTrueJustBelowGainBoundary() {
        // height 1200 → half-split zoom = min(2.0, 1920/1200 = 1.6) → gain 1.6 ≥ 1.5 → split.
        val panel = PanelRegion(x = 0, y = 0, width = 1080, height = 1200)
        assertTrue(PanelOverflowTransform.isOverflowing(panel, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsFalseJustAboveGainBoundary() {
        // height 1350 → half-split zoom = min(2.0, 1920/1350 ≈ 1.42) → gain 1.42 < 1.5 → whole.
        val panel = PanelRegion(x = 0, y = 0, width = 1080, height = 1350)
        assertFalse(PanelOverflowTransform.isOverflowing(panel, imgW, imgH, vpW, vpH))
    }

    @Test
    fun isOverflowingReturnsFalseForZeroViewport() {
        assertFalse(PanelOverflowTransform.isOverflowing(widePanelBanner, imgW, imgH, 0, 0))
    }

    @Test
    fun applyOverflowSplitSplitsTallPanelAtVerticalMidpoint() {
        val result = PanelOverflowTransform.applyOverflow(
            listOf(tallPanel), imgW, imgH, 1920, 1080, PanelOverflowBehavior.SPLIT,
        )
        assertEquals(2, result.size)
        val top = result[0]
        val bottom = result[1]
        assertEquals(tallPanel.x, top.x)
        assertEquals(tallPanel.y, top.y)
        assertEquals(tallPanel.width, top.width)
        assertEquals(712, top.height)   // 1425 / 2 = 712
        assertEquals(tallPanel.x, bottom.x)
        assertEquals(712, bottom.y)
        assertEquals(tallPanel.width, bottom.width)
        assertEquals(713, bottom.height) // 1425 - 712 = 713
    }
}
