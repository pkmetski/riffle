package com.riffle.app.feature.reader

import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: a cross-chapter annotation jump landed on the figure, then the viewport ended up
 * near the start of the chapter above it. The chapter above the target loads deferred, and when
 * it measures its real height the controller compensates with `port.scrollBy(delta)` from inside
 * that WebView's `doOnNextLayout` — i.e. during this view's `onLayout`. `scrollBy(x, y)` is
 * short-circuited for the whole of `onLayout` (to block NestedScrollView's scrollToChild), so the
 * compensation was silently dropped and every slot below the grown chapter shifted under a
 * stationary scroll position.
 *
 * The port must therefore move the scroll even when invoked from a child's layout callback,
 * while the View-level `scrollBy` used by NestedScrollView internals stays suppressed there.
 */
@RunWith(AndroidJUnit4::class)
class ContinuousReaderViewLayoutTimeScrollTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Fixture(val view: ContinuousReaderView, val tall: View)

    /** A laid-out reader (1080x2000) whose container holds one 20 000 px child. */
    private fun laidOutFixture(): Fixture {
        var f: Fixture? = null
        onMain {
            val v = ContinuousReaderView(context)
            val container = v.getChildAt(0) as ViewGroup
            val tall = View(context)
            container.addView(tall, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20_000))
            measureAndLayout(v)
            f = Fixture(v, tall)
        }
        return f!!
    }

    private fun measureAndLayout(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 2000)
    }

    /** Grow [tall] so the next measure+layout pass runs its `doOnNextLayout` listeners. */
    private fun relayoutWithGrownChild(f: Fixture) {
        f.tall.layoutParams = f.tall.layoutParams.also { it.height = 30_000 }
        measureAndLayout(f.view)
    }

    @Test
    fun portScrollBy_fromChildLayoutCallback_movesTheScroll() {
        val f = laidOutFixture()
        onMain {
            f.tall.doOnNextLayout { f.view.scrollPortForTest.scrollBy(1500) }
            relayoutWithGrownChild(f)
        }
        var scrollY = -1
        onMain { scrollY = f.view.scrollY }
        assertEquals(
            "controller compensation issued during the layout pass must not be swallowed",
            1500, scrollY,
        )
    }

    @Test
    fun viewScrollBy_fromChildLayoutCallback_staysSuppressed() {
        // The suppression itself must survive the fix: NestedScrollView's scrollToChild goes
        // through View.scrollBy and still has to be a no-op during onLayout.
        val f = laidOutFixture()
        onMain {
            f.tall.doOnNextLayout { f.view.scrollBy(0, 1500) }
            relayoutWithGrownChild(f)
        }
        var scrollY = -1
        onMain { scrollY = f.view.scrollY }
        assertEquals("View.scrollBy during onLayout must remain suppressed", 0, scrollY)
    }
}
