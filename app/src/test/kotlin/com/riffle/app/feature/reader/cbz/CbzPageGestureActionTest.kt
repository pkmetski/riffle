package com.riffle.app.feature.reader.cbz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the swipe-to-turn fix. Prior implementation used `detectTransformGestures`,
 * which consumed single-finger drags at scale=1, starving HorizontalPager of the
 * swipe. If someone reverts the gesture handler to unconditionally consume, the
 * `Ignore` assertions below flip red.
 */
class CbzPageGestureActionTest {

    @Test
    fun single_finger_at_rest_scale_is_ignored_so_pager_gets_swipe() {
        assertEquals(CbzPageGestureAction.Ignore, cbzPageGestureAction(pointerCount = 1, scale = 1f))
    }

    @Test
    fun zero_pointers_is_ignored() {
        assertEquals(CbzPageGestureAction.Ignore, cbzPageGestureAction(pointerCount = 0, scale = 1f))
        assertEquals(CbzPageGestureAction.Ignore, cbzPageGestureAction(pointerCount = 0, scale = 2f))
    }

    @Test
    fun single_finger_while_zoomed_pans_the_zoomed_page() {
        assertEquals(CbzPageGestureAction.PanZoomed, cbzPageGestureAction(pointerCount = 1, scale = 1.5f))
        assertEquals(CbzPageGestureAction.PanZoomed, cbzPageGestureAction(pointerCount = 1, scale = 5f))
    }

    @Test
    fun two_or_more_fingers_zoom_regardless_of_current_scale() {
        assertEquals(CbzPageGestureAction.Zoom, cbzPageGestureAction(pointerCount = 2, scale = 1f))
        assertEquals(CbzPageGestureAction.Zoom, cbzPageGestureAction(pointerCount = 2, scale = 3f))
        assertEquals(CbzPageGestureAction.Zoom, cbzPageGestureAction(pointerCount = 3, scale = 1f))
    }
}
