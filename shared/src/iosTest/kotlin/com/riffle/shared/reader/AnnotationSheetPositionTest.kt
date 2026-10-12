package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [annotationSheetTargetY].
 *
 * Regression: the annotation sheet was always docked at the bottom of the screen regardless of
 * where the selection was, unlike Android's HighlightPopupPositionProvider which anchors near the
 * tapped rect.
 */
class AnnotationSheetPositionTest {

    /** When the selection is low on the screen there is room to show the sheet above it. */
    @Test
    fun sheetAboveSelectionWhenRoomAvailable() {
        // Selection at y=200, default sheetEst=80, margin=8 → above at 200-80-8=112 ≥ 0.
        val y = annotationSheetTargetY(selectionY = 200f, selectionHeight = 20f)
        assertEquals(112f, y, "sheet should appear above selection when aboveY ≥ 0")
    }

    /** When the selection is near the top of the screen the sheet falls below it. */
    @Test
    fun sheetBelowSelectionWhenNearTop() {
        // Selection at y=40, aboveY = 40-80-8 = -48 < 0 → below at 40+20+8 = 68.
        val y = annotationSheetTargetY(selectionY = 40f, selectionHeight = 20f)
        assertEquals(68f, y, "sheet should fall below selection when there is no room above")
    }

    /** The threshold for "above" is exactly zero: aboveY==0 stays above. */
    @Test
    fun sheetAboveAtExactBoundary() {
        // sheetEst + margin = 88. selectionY = 88 → aboveY = 88-80-8 = 0 → above at 0.
        val y = annotationSheetTargetY(selectionY = 88f, selectionHeight = 10f)
        assertEquals(0f, y, "aboveY==0 is acceptable; sheet pins to the top edge")
    }

    /** Custom parameters are respected. */
    @Test
    fun customSheetEstimateAndMargin() {
        // sheetEst=60, margin=4. selectionY=100 → aboveY=100-60-4=36 ≥ 0.
        val y = annotationSheetTargetY(selectionY = 100f, selectionHeight = 30f, sheetEstimatedHeight = 60f, margin = 4f)
        assertEquals(36f, y)
    }

    /** Result is always non-negative so we never send the sheet above the screen top. */
    @Test
    fun resultIsNeverNegative() {
        // Pathological: selectionY=0, aboveY=-88 < 0 → below at 0+5+8=13.
        val y = annotationSheetTargetY(selectionY = 0f, selectionHeight = 5f)
        assertTrue(y >= 0f, "targetY must never be negative (expected ≥ 0, got $y)")
    }
}
