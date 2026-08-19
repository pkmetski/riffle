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
        val sampler: (PanelRegion) -> FloatArray = { panel ->
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
        val sampler: (PanelRegion) -> FloatArray = { panel ->
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
