package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CbzAutoRotateOrientationTest {

    private val imageWidth = 1000
    private val imageHeight = 1500

    private fun panels(vararg panels: PanelRegion) = PagePanels(
        panels = panels.toList(),
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        pageIndex = 0,
        source = PanelSource.Auto,
    )

    private val autoRotateFormatting = ComicFormattingPreferences(
        panelViewOn = true,
        panelOverflow = PanelOverflowBehavior.AUTO_ROTATE,
    )

    @Test fun `null when panel view off`() {
        val formatting = autoRotateFormatting.copy(panelViewOn = false)
        assertNull(computeAutoRotateOrientation(formatting, 0, panels(PanelRegion(0, 0, 950, 100))))
    }

    @Test fun `null when overflow is OFF`() {
        val formatting = autoRotateFormatting.copy(panelOverflow = PanelOverflowBehavior.OFF)
        assertNull(computeAutoRotateOrientation(formatting, 0, panels(PanelRegion(0, 0, 950, 100))))
    }

    @Test fun `null when overflow is SPLIT`() {
        val formatting = autoRotateFormatting.copy(panelOverflow = PanelOverflowBehavior.SPLIT)
        assertNull(computeAutoRotateOrientation(formatting, 0, panels(PanelRegion(0, 0, 950, 100))))
    }

    @Test fun `null when pagePanels is null`() {
        assertNull(computeAutoRotateOrientation(autoRotateFormatting, 0, null))
    }

    @Test fun `null when pagePanels isFallback`() {
        val fallback = PagePanels(
            panels = listOf(PanelRegion(0, 0, 950, 100)),
            imageWidth = imageWidth, imageHeight = imageHeight,
            pageIndex = 0,
            source = PanelSource.Fallback,
        )
        assertNull(computeAutoRotateOrientation(autoRotateFormatting, 0, fallback))
    }

    @Test fun `null when panelIndex out of bounds`() {
        assertNull(computeAutoRotateOrientation(autoRotateFormatting, 5, panels(PanelRegion(0, 0, 950, 100))))
    }

    @Test fun `wide panel (widthRatio == 0,9) returns SENSOR_LANDSCAPE`() {
        // 900 / 1000 = 0.9 — exactly at threshold
        val result = computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 900, 100)))
        assertEquals(ORIENTATION_SENSOR_LANDSCAPE, result)
    }

    @Test fun `very wide panel (widthRatio over 0,9) returns SENSOR_LANDSCAPE`() {
        val result = computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 1000, 100)))
        assertEquals(ORIENTATION_SENSOR_LANDSCAPE, result)
    }

    @Test fun `narrow panel (widthRatio below 0,9) does not trigger landscape`() {
        // 800 / 1000 = 0.8 — below threshold; heightRatio = 100/1500 also below 0.9
        assertNull(computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 800, 100))))
    }

    @Test fun `tall panel (heightRatio == 0,9) returns SENSOR_PORTRAIT`() {
        // 1350 / 1500 = 0.9 — exactly at threshold; width < 0.9 * 1000
        val result = computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 100, 1350)))
        assertEquals(ORIENTATION_SENSOR_PORTRAIT, result)
    }

    @Test fun `very tall panel (heightRatio over 0,9) returns SENSOR_PORTRAIT`() {
        val result = computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 100, 1500)))
        assertEquals(ORIENTATION_SENSOR_PORTRAIT, result)
    }

    @Test fun `widthRatio wins over heightRatio when both are high`() {
        // A square panel spanning the full image: both ratios == 1.0; widthRatio checked first → landscape
        val result = computeAutoRotateOrientation(autoRotateFormatting, 0, panels(PanelRegion(0, 0, 1000, 1500)))
        assertEquals(ORIENTATION_SENSOR_LANDSCAPE, result)
    }

    @Test fun `correct panel chosen by panelIndex`() {
        // Index 0 = narrow, index 1 = wide
        val page = panels(
            PanelRegion(0, 0, 500, 100),   // index 0 — narrow
            PanelRegion(0, 100, 950, 100), // index 1 — wide
        )
        assertNull(computeAutoRotateOrientation(autoRotateFormatting, 0, page))
        assertEquals(ORIENTATION_SENSOR_LANDSCAPE, computeAutoRotateOrientation(autoRotateFormatting, 1, page))
    }
}
