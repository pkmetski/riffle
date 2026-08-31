package com.riffle.app.feature.reader.cbz

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelReportOutlineStyleTest {

    @Test
    fun `default detected panel outline uses readable layered strokes`() {
        val style = panelReportOutlineStyle(selected = false, ordered = false)

        assertNotEquals(Color.Blue, style.foregroundColor)
        assertEquals(Color(0xFF00B8FF), style.foregroundColor)
        assertEquals(Color(0xE6000000), style.haloColor)
        assertEquals(Color.White, style.contrastColor)
        assertTrue(style.foregroundWidth > 1.5f)
        assertTrue(style.contrastWidth > style.foregroundWidth)
        assertTrue(style.haloWidth > style.contrastWidth)
    }

    @Test
    fun `selected and ordered outlines keep their semantic colors`() {
        assertEquals(Color.Red, panelReportOutlineStyle(selected = true, ordered = false).foregroundColor)
        assertEquals(Color(0xFFFF9800), panelReportOutlineStyle(selected = false, ordered = true).foregroundColor)
    }

    @Test
    fun `drawsRectangle returns true for MergedPanels MissedPanel and SplitPanel`() {
        // Regression: MergedPanels was excluded, causing the drag to draw a line instead of a rect.
        assertTrue(drawsRectangle(PanelDetectionFailureType.MergedPanels))
        assertTrue(drawsRectangle(PanelDetectionFailureType.MissedPanel))
        assertTrue(drawsRectangle(PanelDetectionFailureType.SplitPanel))
    }

    @Test
    fun `drawsRectangle returns false for non-rect failure types`() {
        assertFalse(drawsRectangle(null))
        assertFalse(drawsRectangle(PanelDetectionFailureType.WrongPanelOrder))
        assertFalse(drawsRectangle(PanelDetectionFailureType.FalsePanel))
        assertFalse(drawsRectangle(PanelDetectionFailureType.CutPanelCutOff))
    }
}
