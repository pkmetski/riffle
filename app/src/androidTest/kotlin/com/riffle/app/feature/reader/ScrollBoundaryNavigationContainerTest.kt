package com.riffle.app.feature.reader

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// GestureDetector requires a Looper; view creation and event dispatch run on the main thread
// via runOnMainSync. The handleFling tests call the internal method directly because
// synthetic MotionEvents cannot reliably trigger GestureDetector.onFling without a real
// window and event pump — that pipeline is a well-tested Android framework concern.
@RunWith(AndroidJUnit4::class)
class ScrollBoundaryNavigationContainerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun container(
        isScrollMode: Boolean = true,
        progression: Float = 0f,
    ): ScrollBoundaryNavigationContainer {
        var c: ScrollBoundaryNavigationContainer? = null
        onMain {
            c = ScrollBoundaryNavigationContainer(context).apply {
                this.isScrollMode = isScrollMode
                this.currentProgression = progression
            }
        }
        return c!!
    }

    // -- Fling-based navigation --

    @Test
    fun upwardFlingAtChapterEndInScrollModeInvokesNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 1.0f)
        c.onNavigateForward = { invoked = true }
        // Negative velocityY = finger moves up = reading forward through content.
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertTrue(invoked)
    }

    @Test
    fun upwardFlingJustAboveForwardThresholdInvokesNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.96f)
        c.onNavigateForward = { invoked = true }
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertTrue(invoked)
    }

    @Test
    fun upwardFlingJustBelowForwardThresholdDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.94f)
        c.onNavigateForward = { invoked = true }
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertFalse(invoked)
    }

    @Test
    fun upwardFlingMidChapterDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.5f)
        c.onNavigateForward = { invoked = true }
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertFalse(invoked)
    }

    @Test
    fun downwardFlingAtChapterStartInScrollModeInvokesNavigateBackward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.0f)
        c.onNavigateBackward = { invoked = true }
        // Positive velocityY = finger moves down = reading backward through content.
        c.handleFling(velocityX = 0f, velocityY = 2000f)
        assertTrue(invoked)
    }

    @Test
    fun downwardFlingJustBelowBackwardThresholdInvokesNavigateBackward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.04f)
        c.onNavigateBackward = { invoked = true }
        c.handleFling(velocityX = 0f, velocityY = 2000f)
        assertTrue(invoked)
    }

    @Test
    fun downwardFlingJustAboveBackwardThresholdDoesNotInvokeNavigateBackward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.06f)
        c.onNavigateBackward = { invoked = true }
        c.handleFling(velocityX = 0f, velocityY = 2000f)
        assertFalse(invoked)
    }

    @Test
    fun flingInPaginatedModeInvokesNeitherCallback() {
        var forwardInvoked = false
        var backwardInvoked = false
        val c = container(isScrollMode = false, progression = 1.0f)
        c.onNavigateForward = { forwardInvoked = true }
        c.onNavigateBackward = { backwardInvoked = true }
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertFalse(forwardInvoked)
        assertFalse(backwardInvoked)
    }

    @Test
    fun predominantlyHorizontalFlingAtChapterEndDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 1.0f)
        c.onNavigateForward = { invoked = true }
        // Horizontal fling should be left for the ViewPager, not chapter crossing.
        c.handleFling(velocityX = -3000f, velocityY = -2000f)
        assertFalse(invoked)
    }

    @Test
    fun rapidlyRepeatedFlingFiresNavigationOnlyOnce() {
        var count = 0
        val c = container(isScrollMode = true, progression = 1.0f)
        c.onNavigateForward = { count++ }
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        c.handleFling(velocityX = 0f, velocityY = -2000f)
        assertEquals(1, count)
    }

    // -- Drag-past-boundary navigation --

    @Test
    fun dragPastForwardBoundaryWithStaledProgressionInvokesNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.95f)
        c.onNavigateForward = { invoked = true }
        // Wait for progression to go stale (> STALE_PROGRESSION_MS = 300ms).
        Thread.sleep(ScrollBoundaryNavigationContainer.STALE_PROGRESSION_MS + 50)
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 500f, 0)
            c.dispatchTouchEvent(down)
            down.recycle()
            // 10 moves × 10px = 100px upward drag; exceeds DRAG_THRESHOLD_PX (80px).
            for (i in 1..10) {
                val mt = SystemClock.uptimeMillis()
                val move = MotionEvent.obtain(t, mt, MotionEvent.ACTION_MOVE, 100f, 500f - i * 10f, 0)
                c.dispatchTouchEvent(move)
                move.recycle()
            }
        }
        // Use a second runOnMainSync as a barrier: post{} callbacks queued during the block
        // above are guaranteed to have run before this empty block executes.
        onMain {}
        assertTrue(invoked)
    }

    @Test
    fun dragPastForwardBoundaryWithFreshProgressionDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.95f)
        c.onNavigateForward = { invoked = true }
        // No sleep — progression is fresh, WebView appears to still be scrolling.
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 500f, 0)
            c.dispatchTouchEvent(down)
            down.recycle()
            for (i in 1..10) {
                val mt = SystemClock.uptimeMillis()
                val move = MotionEvent.obtain(t, mt, MotionEvent.ACTION_MOVE, 100f, 500f - i * 10f, 0)
                c.dispatchTouchEvent(move)
                move.recycle()
            }
        }
        onMain {}
        assertFalse(invoked)
    }

    @Test
    fun dragPastForwardBoundaryBelowDragThresholdProgressionDoesNotInvokeNavigateForward() {
        var invoked = false
        // 0.85f is below DRAG_FORWARD_THRESHOLD (0.90f) so drag should not trigger.
        val c = container(isScrollMode = true, progression = 0.85f)
        c.onNavigateForward = { invoked = true }
        Thread.sleep(ScrollBoundaryNavigationContainer.STALE_PROGRESSION_MS + 50)
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 500f, 0)
            c.dispatchTouchEvent(down)
            down.recycle()
            for (i in 1..10) {
                val mt = SystemClock.uptimeMillis()
                val move = MotionEvent.obtain(t, mt, MotionEvent.ACTION_MOVE, 100f, 500f - i * 10f, 0)
                c.dispatchTouchEvent(move)
                move.recycle()
            }
        }
        onMain {}
        assertFalse(invoked)
    }

    // -- Volume key scroll --

    @Test
    fun volumeScrollForwardAtBoundaryInvokesNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.96f)
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true) { /* js not expected */ }
        assertTrue(invoked)
    }

    @Test
    fun volumeScrollForwardMidChapterFiresJsAndDoesNotNavigate() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container(isScrollMode = true, progression = 0.5f)
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true) { js -> jsCapture += js }
        assertFalse(invoked)
        assertEquals(1, jsCapture.size)
        assertTrue(jsCapture[0].contains("behavior: 'smooth'"))
        assertTrue(jsCapture[0].contains("innerHeight * 0.8"))
        assertFalse(jsCapture[0].contains("-("))
    }

    @Test
    fun volumeScrollBackwardAtBoundaryInvokesNavigateBackward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.04f)
        c.onNavigateBackward = { invoked = true }
        c.handleVolumeScroll(forward = false) { /* js not expected */ }
        assertTrue(invoked)
    }

    @Test
    fun volumeScrollBackwardMidChapterFiresJsAndDoesNotNavigate() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container(isScrollMode = true, progression = 0.5f)
        c.onNavigateBackward = { invoked = true }
        c.handleVolumeScroll(forward = false) { js -> jsCapture += js }
        assertFalse(invoked)
        assertEquals(1, jsCapture.size)
        assertTrue(jsCapture[0].contains("behavior: 'smooth'"))
        assertTrue(jsCapture[0].contains("-("))
    }

    @Test
    fun volumeScrollForwardWhenStuckAtBoundaryInvokesNavigateForward() {
        var invoked = false
        // 0.93f is below the 0.95f threshold but WebView can't scroll further.
        val c = container(isScrollMode = true, progression = 0.93f)
        c.onNavigateForward = { invoked = true }
        // First press — not at hard threshold, fires scroll, records progression.
        c.handleVolumeScroll(forward = true) {}
        assertFalse(invoked)
        // Simulate a tiny residual scroll (< VOLUME_SCROLL_EPSILON = 0.02).
        c.currentProgression = 0.935f
        // Second press — moved less than epsilon, treated as stuck, should navigate.
        c.handleVolumeScroll(forward = true) {}
        assertTrue(invoked)
    }

    @Test
    fun volumeScrollForwardRapidPressesWithinCooldownFireOnlyOnce() {
        var count = 0
        val c = container(isScrollMode = true, progression = 0.99f)
        c.onNavigateForward = { count++ }
        // Both calls are within VOLUME_NAV_COOLDOWN_MS of each other.
        c.handleVolumeScroll(forward = true) {}
        c.handleVolumeScroll(forward = true) {}
        assertEquals(1, count)
    }

    @Test
    fun volumeScrollForwardAfterChapterNavigationIsNotBlocked() {
        var count = 0
        val c = container(isScrollMode = true, progression = 0.99f)
        c.onNavigateForward = { count++ }
        // First press navigates.
        c.handleVolumeScroll(forward = true) {}
        assertEquals(1, count)
        // Simulate the new chapter loading at progression = 0.0, past the cooldown.
        Thread.sleep(ScrollBoundaryNavigationContainer.VOLUME_NAV_COOLDOWN_MS + 50)
        c.currentProgression = 0.0f
        // Next press should scroll, not be blocked.
        val jsCapture = mutableListOf<String>()
        c.handleVolumeScroll(forward = true) { js -> jsCapture += js }
        assertEquals(1, jsCapture.size)
        assertEquals(1, count) // no additional navigation
    }

    @Test
    fun volumeScrollInPaginatedModeIsNoOp() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container(isScrollMode = false, progression = 0.5f)
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true) { js -> jsCapture += js }
        assertFalse(invoked)
        assertTrue(jsCapture.isEmpty())
    }

    // -- Touch event passthrough --

    @Test
    fun dispatchTouchEventIsNonConsuming() {
        val c = container(isScrollMode = true, progression = 1.0f)
        c.onNavigateForward = {}
        var consumed = true
        onMain {
            val event = MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN, 100f, 100f, 0,
            )
            consumed = c.dispatchTouchEvent(event)
            event.recycle()
        }
        assertFalse(consumed)
    }
}
