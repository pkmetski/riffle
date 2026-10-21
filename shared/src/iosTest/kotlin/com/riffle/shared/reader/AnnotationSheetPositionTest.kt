package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [annotationSheetTargetY].
 *
 * iOS prefers *below* the selection (unlike Android which prefers above). The iOS selection handles
 * appear AT selectionY and extend upward, so placing the sheet above would cover them. Below the
 * selection there are no handles. The sheet falls back to above only when below would exceed the
 * screen bottom (maxBelowY constraint).
 */
class AnnotationSheetPositionTest {

    @Test
    fun sheetBelowSelectionByDefault() {
        // Prefer below: selectionY=200, height=20, margin=8 → below at 200+20+8=228.
        val y = annotationSheetTargetY(selectionY = 200f, selectionHeight = 20f, maxBelowY = 900f)
        assertEquals(228f, y, "sheet should prefer below the selection on iOS to avoid covering selection handles")
    }

    @Test
    fun sheetBelowWhenBelowFits() {
        // Selection near top but below still fits: selectionY=40, height=20 → below at 68.
        val y = annotationSheetTargetY(selectionY = 40f, selectionHeight = 20f, maxBelowY = 900f)
        assertEquals(68f, y, "sheet should still go below when there is room")
    }

    @Test
    fun sheetFallsAboveWhenBelowExceedsScreen() {
        // Below would be at 720+20+8=748 and sheet height=80 → bottom=828 > maxBelowY=800.
        // Falls back to above: 720-80-8=632.
        val y = annotationSheetTargetY(
            selectionY = 720f,
            selectionHeight = 20f,
            maxBelowY = 800f,
        )
        assertEquals(632f, y, "sheet falls back above when below would go off-screen")
    }

    @Test
    fun customSheetEstimateAndMargin() {
        // sheetEst=60, margin=4: below at 100+30+4=134, fits under maxBelowY=900.
        val y = annotationSheetTargetY(selectionY = 100f, selectionHeight = 30f, sheetEstimatedHeight = 60f, margin = 4f, maxBelowY = 900f)
        assertEquals(134f, y)
    }

    @Test
    fun resultIsNeverNegative() {
        // selectionY=0, height=5 → below at 13, fits. Result should be positive.
        val y = annotationSheetTargetY(selectionY = 0f, selectionHeight = 5f, maxBelowY = 900f)
        assertTrue(y >= 0f, "targetY must never be negative (expected ≥ 0, got $y)")
    }

    @Test
    fun minAboveYPreventsHidingBehindStatusBar_whenFallingBack() {
        // Below would be at 780+20+8=808, sheet bottom 888 > maxBelowY=850. Falls back above.
        // Without minAboveY: aboveY = 780-80-8=692 ≥ minAboveY=50 → returns 692.
        val y = annotationSheetTargetY(selectionY = 780f, selectionHeight = 20f, minAboveY = 50f, maxBelowY = 850f)
        assertEquals(692f, y)
    }

    @Test
    fun minAboveYClampsAboveFallback() {
        // Below: 10+5+8=23, sheet bottom 103 > maxBelowY=80. Falls back above: 10-80-8=-78 → clamps to minAboveY=0.
        val y = annotationSheetTargetY(selectionY = 10f, selectionHeight = 5f, minAboveY = 0f, maxBelowY = 80f)
        assertEquals(0f, y, "above fallback is clamped to minAboveY when computed aboveY is negative")
    }
}
