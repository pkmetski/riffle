package com.riffle.app.feature.reader

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test
    fun downwardFlingAtChapterEndInScrollModeInvokesNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 1.0f)
        c.onNavigateForward = { invoked = true }
        c.handleFling(velocityY = 2000f)
        assertTrue(invoked)
    }

    @Test
    fun downwardFlingMidChapterDoesNotInvokeNavigateForward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.5f)
        c.onNavigateForward = { invoked = true }
        c.handleFling(velocityY = 2000f)
        assertFalse(invoked)
    }

    @Test
    fun upwardFlingAtChapterStartInScrollModeInvokesNavigateBackward() {
        var invoked = false
        val c = container(isScrollMode = true, progression = 0.0f)
        c.onNavigateBackward = { invoked = true }
        c.handleFling(velocityY = -2000f)
        assertTrue(invoked)
    }

    @Test
    fun flingInPaginatedModeInvokesNeitherCallback() {
        var forwardInvoked = false
        var backwardInvoked = false
        val c = container(isScrollMode = false, progression = 1.0f)
        c.onNavigateForward = { forwardInvoked = true }
        c.onNavigateBackward = { backwardInvoked = true }
        c.handleFling(velocityY = 2000f)
        assertFalse(forwardInvoked)
        assertFalse(backwardInvoked)
    }

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
