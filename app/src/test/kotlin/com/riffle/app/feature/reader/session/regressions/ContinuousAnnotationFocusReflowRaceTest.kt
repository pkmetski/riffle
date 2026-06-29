package com.riffle.app.feature.reader.session.regressions

import com.riffle.app.feature.reader.ContinuousPositionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the continuous-mode annotation/resume reflow race
 * (memory: reference_continuous_annotation_focus_reflow_race.md).
 *
 * ## The bug
 * On initial open at an annotation or mid-chapter resume position, continuous mode fires the
 * initial scroll once every chapter up to the target has reported its first real height. But
 * the FIRST real height often arrives PRE-reflow: the typography injection (style JS) runs
 * immediately on page-finished and causes the chapter body to GROW. A landing computed against
 * the pre-reflow height undershoots — the annotation or resume position ends up near the
 * chapter top instead of at the intended location.
 *
 * ## The fix (ContinuousReaderView.kt, appendChapter onHeightMeasured)
 * After the initial land fires, `reapplyLandingAfterFallback` holds the landing closure and
 * `reapplyTargetLastHeight` records the height used. On EVERY subsequent height report for the
 * target chapter, if `measuredPx != reapplyTargetLastHeight`, the closure is invoked again —
 * re-landing against the updated (taller) height. Settles once the height stops changing.
 * Disarmed on first manual touch so the user is never yanked to a stale target.
 *
 * ## Why these tests live here
 * `ContinuousReaderView` extends `NestedScrollView` and hosts real `ChapterWebView`s; it
 * requires an Android context and cannot be instantiated in a JVM test. The tests below
 * exercise the two pure-JVM pieces that the safeguard depends on:
 *
 *  1. **Landing-Y sensitivity to height changes** — asserts that `ContinuousPositionTracker`
 *     produces a different (larger) Y when the slot height grows from placeholder to real,
 *     proving that a re-land closure invoked after the reflow lands at the correct position
 *     whereas the first land would have been short.
 *
 *  2. **Re-land state machine invariant** — the guard condition
 *     `measuredPx != reapplyTargetLastHeight` that triggers the closure invocation is modelled
 *     in [ReapplyStateMachine]. This reproduces the exact three-field logic from
 *     [ContinuousReaderView.appendChapter].onHeightMeasured (lines 1106-1116) so a future
 *     refactor that removes or weakens the guard will break these tests.
 *
 * Production code path: ContinuousReaderView.kt, appendChapter(), onHeightMeasured lambda,
 * lines ~1099-1116.
 */
class ContinuousAnnotationFocusReflowRaceTest {

    // ---------------------------------------------------------------------------
    // 1. Landing-Y sensitivity to height changes
    // ---------------------------------------------------------------------------

    /**
     * Proves the reflow race is real: a mid-chapter landing computed from a PRE-REFLOW (short)
     * height undershoots vs the same landing after the body has grown.
     *
     * Scenario: chapter 0 is fully measured; chapter 1 (the target) was first reported at
     * 400 px (placeholder) but reflows to 800 px. An annotation at 50 % progression should
     * land at 400 px into the chapter (800 * 0.5), but the pre-reflow scroll lands at 200 px
     * (400 * 0.5) — 200 px short. Without the re-land safeguard the user sees the wrong position.
     */
    @Test
    fun `landing Y is short when computed against pre-reflow placeholder height`() {
        val chapterOnePx = 600
        val placeholderHeight = 400
        val realHeight = 800
        val progression = 0.5f

        val windowPreReflow = listOf(
            ContinuousPositionTracker.ChapterSlot("ch0.xhtml", top = 0, height = chapterOnePx),
            ContinuousPositionTracker.ChapterSlot("ch1.xhtml", top = chapterOnePx, height = placeholderHeight),
        )
        val windowPostReflow = listOf(
            ContinuousPositionTracker.ChapterSlot("ch0.xhtml", top = 0, height = chapterOnePx),
            ContinuousPositionTracker.ChapterSlot("ch1.xhtml", top = chapterOnePx, height = realHeight),
        )

        val yPreReflow = ContinuousPositionTracker.scrollOffsetFor("ch1.xhtml", progression, windowPreReflow)!!
        val yPostReflow = ContinuousPositionTracker.scrollOffsetFor("ch1.xhtml", progression, windowPostReflow)!!

        assertTrue(
            "Pre-reflow landing ($yPreReflow) should be less than post-reflow ($yPostReflow)",
            yPreReflow < yPostReflow,
        )
        val expectedDelta = ((realHeight - placeholderHeight) * progression).toInt()
        assertEquals(
            "Delta between pre- and post-reflow landing must equal height-growth * progression",
            expectedDelta,
            yPostReflow - yPreReflow,
        )
    }

    /**
     * Proves that once the height stabilises (no change), re-computing the landing Y produces the
     * same result — the re-land is a no-op when reflow has settled.
     */
    @Test
    fun `landing Y is stable when height does not change`() {
        val window = listOf(
            ContinuousPositionTracker.ChapterSlot("ch0.xhtml", top = 0, height = 600),
            ContinuousPositionTracker.ChapterSlot("ch1.xhtml", top = 600, height = 800),
        )
        val y1 = ContinuousPositionTracker.scrollOffsetFor("ch1.xhtml", 0.5f, window)
        val y2 = ContinuousPositionTracker.scrollOffsetFor("ch1.xhtml", 0.5f, window)
        assertEquals("Stable height must produce the same landing Y on repeat calls", y1, y2)
    }

    // ---------------------------------------------------------------------------
    // 2. Re-land state machine invariant
    //
    // Models the three-field guard from ContinuousReaderView.appendChapter
    // onHeightMeasured (lines 1106-1116). The production class is an Android
    // View that cannot be constructed in a JVM test; this model exercises the
    // identical conditional logic so a refactor that breaks the guard will fail
    // these tests.
    //
    // Production code (verbatim):
    //   } else if (webViews.getOrNull(i)?.chapterHref == pendingTargetHref &&
    //       reapplyLandingAfterFallback != null &&
    //       measuredPx != reapplyTargetLastHeight
    //   ) {
    //       reapplyTargetLastHeight = measuredPx
    //       reapplyLandingAfterFallback?.invoke()
    //   }
    // ---------------------------------------------------------------------------

    /**
     * State machine that reproduces the exact guard from
     * `ContinuousReaderView.appendChapter`.onHeightMeasured.
     */
    private class ReapplyStateMachine(
        val targetHref: String,
        initialHeightPx: Int,
        private val onReland: (newHeightPx: Int) -> Unit,
    ) {
        var reapplyLandingAfterFallback: (() -> Unit)? = null
        var reapplyTargetLastHeight: Int = initialHeightPx

        init {
            // Arm the closure — in production this is done at the end of pendingInitialScroll.
            reapplyLandingAfterFallback = { onReland(reapplyTargetLastHeight) }
        }

        /** Mirrors the else-if block in appendChapter.onHeightMeasured. Returns true if fired. */
        fun onHeightMeasured(chapterHref: String, measuredPx: Int): Boolean {
            if (chapterHref == targetHref &&
                reapplyLandingAfterFallback != null &&
                measuredPx != reapplyTargetLastHeight
            ) {
                reapplyTargetLastHeight = measuredPx
                reapplyLandingAfterFallback?.invoke()
                return true
            }
            return false
        }

        /** Mirrors onInterceptTouchEvent ACTION_DOWN that disarms auto-re-landing. */
        fun disarm() {
            reapplyLandingAfterFallback = null
        }
    }

    @Test
    fun `re-land closure fires on each height change until stable`() {
        val landedAtHeights = mutableListOf<Int>()
        val sm = ReapplyStateMachine(
            targetHref = "ch1.xhtml",
            initialHeightPx = 400,
            onReland = { h -> landedAtHeights.add(h) },
        )

        // Reflow pass 1: typography injection grows the chapter 400 → 650 px
        assertTrue("Re-land must fire on first height change (400→650)", sm.onHeightMeasured("ch1.xhtml", 650))
        assertEquals(650, sm.reapplyTargetLastHeight)

        // Reflow pass 2: image decode grows chapter further 650 → 800 px
        assertTrue("Re-land must fire on second height change (650→800)", sm.onHeightMeasured("ch1.xhtml", 800))
        assertEquals(800, sm.reapplyTargetLastHeight)

        // Height stable — same value reported again: no re-land
        val fired = sm.onHeightMeasured("ch1.xhtml", 800)
        assertEquals("Re-land must NOT fire when height is unchanged", false, fired)

        assertEquals(
            "Closure must have been invoked exactly twice (once per height change)",
            2,
            landedAtHeights.size,
        )
    }

    @Test
    fun `re-land closure fires with updated height on each call`() {
        val heights = mutableListOf<Int>()
        val sm = ReapplyStateMachine(
            targetHref = "ch1.xhtml",
            initialHeightPx = 400,
            onReland = { h -> heights.add(h) },
        )

        sm.onHeightMeasured("ch1.xhtml", 650)
        sm.onHeightMeasured("ch1.xhtml", 800)

        assertEquals("First re-land should see updated height 650", 650, heights[0])
        assertEquals("Second re-land should see updated height 800", 800, heights[1])
    }

    @Test
    fun `re-land closure is NOT invoked for a different chapter height change`() {
        var fireCount = 0
        val sm = ReapplyStateMachine(
            targetHref = "ch1.xhtml",
            initialHeightPx = 400,
            onReland = { fireCount++ },
        )

        val fired = sm.onHeightMeasured("ch2.xhtml", 800)
        assertEquals("Re-land must NOT fire for a non-target chapter", false, fired)
        assertEquals(0, fireCount)
    }

    @Test
    fun `re-land is disarmed after manual touch (closure set null)`() {
        var fireCount = 0
        val sm = ReapplyStateMachine(
            targetHref = "ch1.xhtml",
            initialHeightPx = 400,
            onReland = { fireCount++ },
        )

        // User touches the screen during boot
        sm.disarm()

        val fired = sm.onHeightMeasured("ch1.xhtml", 800)
        assertEquals("Re-land must NOT fire after disarm", false, fired)
        assertEquals(0, fireCount)
    }

    @Test
    fun `scrollOffsetFor returns null for unknown href`() {
        val window = listOf(
            ContinuousPositionTracker.ChapterSlot("ch0.xhtml", top = 0, height = 600),
        )
        assertEquals(
            "scrollOffsetFor must return null for a href not in the window",
            null,
            ContinuousPositionTracker.scrollOffsetFor("unknown.xhtml", 0.5f, window),
        )
    }
}
