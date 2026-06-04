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

// View creation and event dispatch run on the main thread via runOnMainSync.
@RunWith(AndroidJUnit4::class)
class ScrollBoundaryNavigationContainerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun container(
        isScrollMode: Boolean = true,
        atForwardBoundary: Boolean = false,
        atBackwardBoundary: Boolean = false,
        canNavigateForward: Boolean = true,
        canNavigateBackward: Boolean = true,
    ): ScrollBoundaryNavigationContainer {
        var c: ScrollBoundaryNavigationContainer? = null
        onMain {
            c = ScrollBoundaryNavigationContainer(context).apply {
                this.isScrollMode = isScrollMode
                this.atForwardBoundary = atForwardBoundary
                this.atBackwardBoundary = atBackwardBoundary
                this.canNavigateForward = canNavigateForward
                this.canNavigateBackward = canNavigateBackward
            }
        }
        return c!!
    }

    private fun dispatchDown(c: ScrollBoundaryNavigationContainer, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        c.dispatchTouchEvent(down)
        down.recycle()
    }

    private fun dispatchMove(c: ScrollBoundaryNavigationContainer, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x, y, 0)
        c.dispatchTouchEvent(move)
        move.recycle()
    }

    private fun dispatchUp(c: ScrollBoundaryNavigationContainer, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, y, 0)
        c.dispatchTouchEvent(up)
        up.recycle()
    }

    // -- Pull-past-boundary navigation --

    private fun pullThresholdPx(): Float =
        ScrollBoundaryNavigationContainer.PULL_DISTANCE_THRESHOLD_DP *
            context.resources.displayMetrics.density

    // Helper: dispatch MOVE events with real-time spacing so the container's per-ms fill-rate
    // cap accepts them. With MAX_FILL_RATE_DP_PER_MS = 0.25, a 50 ms interval permits up to
    // 12.5 dp of accumulation per move — enough to admit 10 dp of dy per step at any density.
    private fun simulatePull(c: ScrollBoundaryNavigationContainer, forward: Boolean) {
        val thresh = pullThresholdPx()
        val perMoveDy = 10f
        val intervalMs = 50L
        val density = context.resources.displayMetrics.density
        val accumPerMove = perMoveDy.coerceAtMost(
            ScrollBoundaryNavigationContainer.MAX_FILL_RATE_DP_PER_MS * density * intervalMs
        )
        val moves = (thresh / accumPerMove).toInt() + 4
        val startY = if (forward) 1000f else 100f
        onMain { dispatchDown(c, 100f, startY) }
        for (i in 1..moves) {
            Thread.sleep(intervalMs)
            val y = if (forward) startY - i * perMoveDy else startY + i * perMoveDy
            onMain { dispatchMove(c, 100f, y) }
        }
        onMain {}
    }

    @Test
    fun forwardPullPastThresholdInvokesNavigateForward() {
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        simulatePull(c, forward = true)
        assertTrue(invoked)
    }

    @Test
    fun forwardPullNotAtBoundaryDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(atForwardBoundary = false)
        c.onNavigateForward = { invoked = true }
        simulatePull(c, forward = true)
        assertFalse(invoked)
    }

    @Test
    fun shortForwardPullAtBoundaryDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        onMain {
            dispatchDown(c, 100f, 500f)
            dispatchMove(c, 100f, 480f) // only ~20 px, well under threshold
            dispatchUp(c, 100f, 480f)
        }
        onMain {}
        assertFalse(invoked)
    }

    @Test
    fun fastSwipeAtForwardBoundaryDoesNotInvokeNavigateForward() {
        // Regression: a sudden fast swipe must not trigger nav. The accumulator's fill-rate
        // cap prevents distance from saturating when finger motion outpaces it.
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        val thresh = pullThresholdPx()
        onMain {
            dispatchDown(c, 100f, 1000f)
            // Cover well over the threshold of dy in a tight loop — wall clock barely
            // advances, so deltaMs ≈ 0 and the accumulator can't catch up to raw motion.
            val swipeDistance = thresh * 2f
            val steps = 20
            for (i in 1..steps) dispatchMove(c, 100f, 1000f - (i / steps.toFloat()) * swipeDistance)
            dispatchUp(c, 100f, 1000f - swipeDistance)
        }
        onMain {}
        assertFalse(invoked)
    }

    @Test
    fun reversingDirectionMidPullCancelsNavigation() {
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        onMain {
            dispatchDown(c, 100f, 1000f)
        }
        // Pull a few real-time spaced moves upward, then reverse — accumulator should reset.
        for (i in 1..3) {
            Thread.sleep(50)
            onMain { dispatchMove(c, 100f, 1000f - i * 10f) }
        }
        Thread.sleep(50)
        onMain { dispatchMove(c, 100f, 1050f) } // reverse direction cancels
        // Now try to drive a full pull from the reversed position — should not trigger
        // because the new motion is going the wrong way for forward.
        for (i in 1..10) {
            Thread.sleep(50)
            onMain { dispatchMove(c, 100f, 1050f + i * 10f) }
        }
        onMain {}
        assertFalse(invoked)
    }

    @Test
    fun leavingBoundaryMidPullCancelsNavigation() {
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        onMain { dispatchDown(c, 100f, 1000f) }
        for (i in 1..3) {
            Thread.sleep(50)
            onMain { dispatchMove(c, 100f, 1000f - i * 10f) }
        }
        // WebView regained scroll headroom mid-pull; subsequent moves should be ignored.
        c.atForwardBoundary = false
        for (i in 4..30) {
            Thread.sleep(50)
            onMain { dispatchMove(c, 100f, 1000f - i * 10f) }
        }
        onMain {}
        assertFalse(invoked)
    }

    @Test
    fun forwardPullAtLastChapterDoesNotArmPullOrNavigate() {
        // Pull-up on the very last chapter: nowhere forward to go, so no pill and no nav.
        var navigated = false
        var pillShown = false
        val c = container(atForwardBoundary = true, canNavigateForward = false)
        c.onNavigateForward = { navigated = true }
        c.onPullStarted = { pillShown = true }
        simulatePull(c, forward = true)
        assertFalse(navigated)
        assertFalse(pillShown)
    }

    @Test
    fun backwardPullAtFirstChapterDoesNotArmPullOrNavigate() {
        // Pull-down on the very first chapter: nowhere back to go, so no pill and no nav.
        var navigated = false
        var pillShown = false
        val c = container(atBackwardBoundary = true, canNavigateBackward = false)
        c.onNavigateBackward = { navigated = true }
        c.onPullStarted = { pillShown = true }
        simulatePull(c, forward = false)
        assertFalse(navigated)
        assertFalse(pillShown)
    }

    @Test
    fun backwardPullPastThresholdInvokesNavigateBackward() {
        var invoked = false
        val c = container(atBackwardBoundary = true)
        c.onNavigateBackward = { invoked = true }
        simulatePull(c, forward = false)
        assertTrue(invoked)
    }

    @Test
    fun horizontalSwipeAtForwardBoundaryDoesNotInvokeNavigateForward() {
        // Regression: a fast sideways swipe with tiny vertical drift at a chapter
        // boundary must not be interpreted as a deliberate pull.
        var invoked = false
        val c = container(atForwardBoundary = true)
        c.onNavigateForward = { invoked = true }
        onMain {
            dispatchDown(c, 100f, 500f)
            // Wide horizontal travel, with small downward drift (away from forward pull dir).
            for (i in 1..20) dispatchMove(c, 100f + i * 30f, 500f + i * 0.5f)
            dispatchUp(c, 700f, 510f)
        }
        onMain {}
        assertFalse(invoked)
    }

    // -- Chapter-nav suppression on ACTION_UP --
    //
    // The container lets MOVE events pass through to the WebView so vertical scrolling and
    // flings work normally. It only intercepts ACTION_UP when R2WebView would have triggered
    // chapter nav (xDiff > slop at some point AND |yDelta| < 200 px at UP). The WebView
    // then receives ACTION_CANCEL instead of ACTION_UP — its CANCEL branch clears the drag
    // flag without invoking nav.

    private fun runGesture(
        c: ScrollBoundaryNavigationContainer,
        startX: Float,
        startY: Float,
        moves: List<Pair<Float, Float>>,
        endAction: Int = MotionEvent.ACTION_UP,
    ): Boolean {
        var interceptedAtEnd = false
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, startX, startY, 0)
            c.onInterceptTouchEvent(down); down.recycle()
            for ((x, y) in moves) {
                val move = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x, y, 0)
                c.onInterceptTouchEvent(move); move.recycle()
            }
            val last = moves.lastOrNull() ?: (startX to startY)
            val up = MotionEvent.obtain(t, t, endAction, last.first, last.second, 0)
            interceptedAtEnd = c.onInterceptTouchEvent(up); up.recycle()
        }
        return interceptedAtEnd
    }

    @Test
    fun horizontalSwipeWithSmallYDeltaInterceptsUp() {
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val intercepted = runGesture(c, 100f, 500f, listOf(100f + slop * 2f to 500f))
        assertTrue(intercepted)
    }

    @Test
    fun pureVerticalSwipeDoesNotInterceptUp() {
        // No horizontal motion past slop, so R2WebView wouldn't navigate either. Pass through.
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val intercepted = runGesture(c, 100f, 500f, listOf(100f to 500f + slop * 10f))
        assertFalse(intercepted)
    }

    @Test
    fun diagonalSwipeWithSmallYDeltaInterceptsUp() {
        // dy > dx during MOVE but final |yDelta| < 200. R2WebView's mIsBeingDragged is set on
        // xDiff > slop regardless of dy, and its UP nav check only requires |yDelta| < 200,
        // so we must intercept.
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val intercepted = runGesture(c, 100f, 500f, listOf(100f + slop * 2f to 500f + slop * 4f))
        assertTrue(intercepted)
    }

    @Test
    fun longVerticalSwipeWithHorizontalDriftDoesNotInterceptUp() {
        // |yDelta| >= 200 means R2WebView would NOT navigate even though xDiff > slop. We
        // must let ACTION_UP through so the WebView's fling can fire.
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val intercepted = runGesture(c, 100f, 500f, listOf(100f + slop * 2f to 500f + 250f))
        assertFalse(intercepted)
    }

    @Test
    fun requestDisallowInterceptIsIgnoredInScrollMode() {
        // R2WebView calls requestDisallowInterceptTouchEvent(true) when the touch starts in
        // the 30 px edge gutter. Honoring that would lock us out for the rest of the gesture
        // and let the edge-started horizontal swipe reach the WebView's nav path.
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var interceptedUp = false
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5f, 500f, 0)
            c.onInterceptTouchEvent(down); down.recycle()
            c.requestDisallowInterceptTouchEvent(true)
            val move = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 5f + slop * 2f, 500f, 0)
            c.onInterceptTouchEvent(move); move.recycle()
            val up = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 5f + slop * 2f, 500f, 0)
            interceptedUp = c.onInterceptTouchEvent(up); up.recycle()
        }
        assertTrue(interceptedUp)
    }

    @Test
    fun horizontalMotionBelowTouchSlopDoesNotInterceptUp() {
        // Sub-slop horizontal jitter (taps with tiny finger drift) must pass through cleanly.
        val c = container()
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val intercepted = runGesture(c, 100f, 500f, listOf(100f + slop - 1f to 500f))
        assertFalse(intercepted)
    }

    @Test
    fun horizontalSwipeInPaginatedModeDoesNotInterceptUp() {
        val c = container(isScrollMode = false)
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var interceptedUp = false
        onMain {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 500f, 0)
            c.onInterceptTouchEvent(down); down.recycle()
            val move = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 100f + slop * 2f, 500f, 0)
            c.onInterceptTouchEvent(move); move.recycle()
            val up = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 100f + slop * 2f, 500f, 0)
            interceptedUp = c.onInterceptTouchEvent(up); up.recycle()
        }
        assertFalse(interceptedUp)
    }

    // -- Volume key scroll --

    @Test
    fun volumeScrollForwardAtBoundaryInvokesNavigateForward() {
        var invoked = false
        val c = container()
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true, atBoundary = true) { /* js not expected */ }
        assertTrue(invoked)
    }

    @Test
    fun volumeScrollForwardMidChapterFiresJsAndDoesNotNavigate() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container()
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true, atBoundary = false) { js -> jsCapture += js }
        assertFalse(invoked)
        assertEquals(1, jsCapture.size)
        assertTrue(jsCapture[0].contains("behavior: 'smooth'"))
        assertTrue(jsCapture[0].contains("innerHeight * 0.8"))
        assertFalse(jsCapture[0].contains("-("))
    }

    @Test
    fun volumeScrollBackwardAtBoundaryInvokesNavigateBackward() {
        var invoked = false
        val c = container()
        c.onNavigateBackward = { invoked = true }
        c.handleVolumeScroll(forward = false, atBoundary = true) { /* js not expected */ }
        assertTrue(invoked)
    }

    @Test
    fun volumeScrollBackwardMidChapterFiresJsAndDoesNotNavigate() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container()
        c.onNavigateBackward = { invoked = true }
        c.handleVolumeScroll(forward = false, atBoundary = false) { js -> jsCapture += js }
        assertFalse(invoked)
        assertEquals(1, jsCapture.size)
        assertTrue(jsCapture[0].contains("behavior: 'smooth'"))
        assertTrue(jsCapture[0].contains("-("))
    }

    @Test
    fun volumeScrollForwardRapidPressesWithinCooldownFireOnlyOnce() {
        var count = 0
        val c = container()
        c.onNavigateForward = { count++ }
        c.handleVolumeScroll(forward = true, atBoundary = true) {}
        c.handleVolumeScroll(forward = true, atBoundary = true) {}
        assertEquals(1, count)
    }

    @Test
    fun volumeScrollForwardAfterChapterNavigationIsNotBlocked() {
        var count = 0
        val c = container()
        c.onNavigateForward = { count++ }
        c.handleVolumeScroll(forward = true, atBoundary = true) {}
        assertEquals(1, count)
        Thread.sleep(ScrollBoundaryNavigationContainer.VOLUME_NAV_COOLDOWN_MS + 50)
        val jsCapture = mutableListOf<String>()
        c.handleVolumeScroll(forward = true, atBoundary = false) { js -> jsCapture += js }
        assertEquals(1, jsCapture.size)
        assertEquals(1, count)
    }

    @Test
    fun volumeScrollInPaginatedModeIsNoOp() {
        var invoked = false
        val jsCapture = mutableListOf<String>()
        val c = container(isScrollMode = false)
        c.onNavigateForward = { invoked = true }
        c.handleVolumeScroll(forward = true, atBoundary = false) { js -> jsCapture += js }
        assertFalse(invoked)
        assertTrue(jsCapture.isEmpty())
    }

    // -- Touch event passthrough --

    @Test
    fun dispatchTouchEventIsNonConsuming() {
        val c = container(atForwardBoundary = true)
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
