package com.riffle.feature.reader.ui

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PanelReportOutlineStyleTest {

    @Test
    fun default_detected_panel_outline_uses_readable_layered_strokes() {
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
    fun selected_and_ordered_outlines_keep_their_semantic_colors() {
        assertEquals(Color.Red, panelReportOutlineStyle(selected = true, ordered = false).foregroundColor)
        assertEquals(Color(0xFFFF9800), panelReportOutlineStyle(selected = false, ordered = true).foregroundColor)
    }

    @Test
    fun drawsRectangle_returns_true_for_MergedPanels_MissedPanel_SplitPanel() {
        assertTrue(drawsRectangle(PanelDetectionFailureType.MergedPanels))
        assertTrue(drawsRectangle(PanelDetectionFailureType.MissedPanel))
        assertTrue(drawsRectangle(PanelDetectionFailureType.SplitPanel))
    }

    @Test
    fun drawsRectangle_returns_false_for_non_rect_failure_types() {
        assertFalse(drawsRectangle(null))
        assertFalse(drawsRectangle(PanelDetectionFailureType.WrongPanelOrder))
        assertFalse(drawsRectangle(PanelDetectionFailureType.FalsePanel))
        assertFalse(drawsRectangle(PanelDetectionFailureType.CutPanelCutOff))
    }
}
