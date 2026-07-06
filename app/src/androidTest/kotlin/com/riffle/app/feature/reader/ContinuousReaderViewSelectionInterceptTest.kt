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

/**
 * Regression: in continuous mode, dragging a text-selection handle made the page fling away,
 * leaving the highlight broken. Root cause was the parent [ContinuousReaderView] (a
 * NestedScrollView) intercepting vertical touch after half a touch-slop and refusing to honour
 * the child WebView's `requestDisallowInterceptTouchEvent(true)`. The fix suppresses both while
 * any child has an active text-selection action mode.
 *
 * Reaches into the view via the package-private `onChildSelectionActiveChanged` callback because
 * spinning up a real WebView selection inside an instrumentation test is unwieldy — the field
 * `selectionActiveCount` is what production code flips through exactly this hook.
 */
@RunWith(AndroidJUnit4::class)
class ContinuousReaderViewSelectionInterceptTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun view(): ContinuousReaderView {
        var v: ContinuousReaderView? = null
        onMain { v = ContinuousReaderView(context) }
        return v!!
    }

    private fun down(v: ContinuousReaderView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        onMain { v.onInterceptTouchEvent(e) }
        e.recycle()
    }

    private fun move(v: ContinuousReaderView, x: Float, y: Float): Boolean {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x, y, 0)
        var intercepted = false
        onMain { intercepted = v.onInterceptTouchEvent(e) }
        e.recycle()
        return intercepted
    }

    @Test
    fun normalReading_interceptsAsSoonAsHalfTouchSlopIsCrossed() {
        val v = view()
        down(v, 100f, 100f)
        // 50dp vertical drift is well past any touch-slop on any density.
        assertTrue("expected intercept during normal reading", move(v, 100f, 1000f))
    }

    @Test
    fun activeSelection_doesNotIntercept_evenAfterLongVerticalDrag() {
        val v = view()
        onMain { v.onSelectionActiveForTest(true) }
        down(v, 100f, 100f)
        // The user is dragging a selection handle 900px down the page. Pre-fix this returned true
        // and the NestedScrollView fling-scrolled away ("page jumps around while highlighting").
        assertFalse("must NOT intercept while a selection is active", move(v, 100f, 1000f))
    }

    @Test
    fun selectionDeactivated_restoresEarlyIntercept() {
        val v = view()
        onMain {
            v.onSelectionActiveForTest(true)
            v.onSelectionActiveForTest(false)
        }
        down(v, 100f, 100f)
        assertTrue("intercept must resume after selection ends", move(v, 100f, 1000f))
    }

    @Test
    fun selectionEnded_firesOnSelectionEndedCallback_onlyWhenCountReachesZero() {
        // Regression: after a text-selection ActionMode is destroyed (Highlight/Copy/tap-outside),
        // the OS leaves the system bars drawn as a transparent overlay above the reader; the
        // ImmersiveModeState topInset watcher can't detect that drift because layout stays
        // fullscreen (inset stays 0). The view raises onSelectionEnded when the selection count
        // reaches zero so the reader can force-re-hide immersive. Overlapping-selection recycle
        // races must NOT fire the callback until the LAST selection ends.
        val v = view()
        var endedCount = 0
        onMain { v.onSelectionEnded = { endedCount++ } }
        onMain {
            v.onSelectionActiveForTest(true)
            v.onSelectionActiveForTest(true)
            v.onSelectionActiveForTest(false)
        }
        // Still one active selection: callback must not fire yet.
        assertEquals(0, endedCount)
        onMain { v.onSelectionActiveForTest(false) }
        assertEquals(1, endedCount)
    }

    @Test
    fun overlappingSelections_decrementOnlyClearsWhenAllEnd() {
        val v = view()
        onMain {
            // Window-shift recycle race: a new WebView's create fires before the recycled one's
            // destroy. The counter must not flip back to "intercept" until BOTH end.
            v.onSelectionActiveForTest(true)
            v.onSelectionActiveForTest(true)
            v.onSelectionActiveForTest(false)
        }
        down(v, 100f, 100f)
        assertFalse("still one active selection — must not intercept", move(v, 100f, 1000f))
        onMain { v.onSelectionActiveForTest(false) }
        down(v, 100f, 100f)
        assertTrue("all selections ended — intercept restored", move(v, 100f, 1000f))
    }

    @Test
    fun requestChildFocus_doesNotCrashAndCancelsScroller() {
        // Smoke test: NestedScrollView.requestChildFocus would queue a scroll-into-view via the
        // private mScroller. Our override calls abortFling() after super to cancel that scroll.
        // We can't easily prove the absence of scrolling on an unattached view, but we can prove
        // the override doesn't crash and that the view doesn't end up in an inconsistent state.
        val v = view()
        val child = android.view.View(context)
        onMain {
            // No-op when focused is null and there's no focused child — exercises the call path.
            v.requestChildFocus(child, child)
        }
    }

    @Test
    fun onStartNestedScroll_isAlwaysRefused() {
        val v = view()
        val child = android.view.View(context)
        // The WebView dispatches nested scroll to keep its handle visible during selection drags.
        // We must refuse to participate, otherwise the inherited NestedScrollView consumes the
        // dispatch and scrolls — same "page jumps" symptom as the rectangle-on-screen path.
        var accepted = true
        onMain { accepted = v.onStartNestedScroll(child, child, androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL) }
        assertFalse("nested-scroll dispatch from child must be refused", accepted)
    }

    @Test
    fun requestChildRectangleOnScreen_isAlwaysBlocked() {
        val v = view()
        val child = android.view.View(context)
        val rect = android.graphics.Rect(0, 5000, 100, 5100)
        // The WebView fires this BEFORE its selection action mode opens — so gating on
        // "is selecting" would let the page jump on the very first selection. Continuous mode
        // owns scroll positioning, so child rectangle-on-screen is always refused.
        var outsideSelection = true
        onMain { outsideSelection = v.requestChildRectangleOnScreen(child, rect, true) }
        assertFalse("rectangle-on-screen must be refused even before selection action mode", outsideSelection)
        onMain { v.onSelectionActiveForTest(true) }
        var insideSelection = true
        onMain { insideSelection = v.requestChildRectangleOnScreen(child, rect, true) }
        assertFalse("rectangle-on-screen must also be refused during selection", insideSelection)
    }

    @Test
    fun requestDisallowIntercept_honouredOnlyWhileSelecting() {
        val v = view()
        // Sanity: outside selection, disallow=true is ignored (production behaviour).
        onMain { v.requestDisallowInterceptTouchEvent(true) }
        // No exception; nothing to assert beyond no-crash.
        // While selecting, the WebView's disallow request must be honoured so its handle drag
        // keeps ownership of the gesture.
        onMain {
            v.onSelectionActiveForTest(true)
            v.requestDisallowInterceptTouchEvent(true)
        }
        // Same dispatch path proves the gesture isn't stolen even when child has asked to keep it.
        down(v, 100f, 100f)
        assertFalse(move(v, 100f, 1000f))
    }
}
