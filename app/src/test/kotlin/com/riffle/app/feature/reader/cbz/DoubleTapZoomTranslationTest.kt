package com.riffle.app.feature.reader.cbz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the double-tap zoom translation math.
 *
 * When the user double-taps at position (tapX, tapY), the graphicsLayer translation
 * must shift the image so that the tapped pixel stays under the finger after scaling.
 * The scale pivot is the center of the composable, so the required offsets are:
 *
 *   tx = (tapX - containerWidth/2)  * (1 - targetScale)
 *   ty = (tapY - containerHeight/2) * (1 - targetScale)
 *
 * If doubleTapZoomTranslation is ever reverted or the formula changed, these assertions
 * flip red.
 */
class DoubleTapZoomTranslationTest {

    @Test
    fun tap_at_center_produces_no_translation() {
        val (tx, ty) = doubleTapZoomTranslation(500f, 1000f, 1000f, 2000f, 2.5f)
        assertEquals(0f, tx, 0.001f)
        assertEquals(0f, ty, 0.001f)
    }

    @Test
    fun tap_at_top_left_shifts_image_down_and_right() {
        // (0 - 500) * (1 - 2) = 500,  (0 - 1000) * (1 - 2) = 1000
        val (tx, ty) = doubleTapZoomTranslation(0f, 0f, 1000f, 2000f, 2f)
        assertEquals(500f, tx, 0.001f)
        assertEquals(1000f, ty, 0.001f)
    }

    @Test
    fun tap_at_bottom_right_shifts_image_up_and_left() {
        // (1000 - 500) * (1 - 2) = -500,  (2000 - 1000) * (1 - 2) = -1000
        val (tx, ty) = doubleTapZoomTranslation(1000f, 2000f, 1000f, 2000f, 2f)
        assertEquals(-500f, tx, 0.001f)
        assertEquals(-1000f, ty, 0.001f)
    }

    @Test
    fun scale_1x_produces_no_translation_regardless_of_tap() {
        val (tx, ty) = doubleTapZoomTranslation(100f, 300f, 1000f, 2000f, 1f)
        assertEquals(0f, tx, 0.001f)
        assertEquals(0f, ty, 0.001f)
    }
}
