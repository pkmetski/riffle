package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterWindowManagerTest {

    private fun slot(href: String, top: Int, height: Int) =
        ContinuousPositionTracker.ChapterSlot(href, top, height)

    private fun uniformWindow(count: Int, chapterHeight: Int = 3000): List<ContinuousPositionTracker.ChapterSlot> {
        var top = 0
        return List(count) { i ->
            slot("ch$i", top, chapterHeight).also { top += chapterHeight }
        }
    }

    // ── basic decisions ──────────────────────────────────────────────────────

    @Test
    fun `holds when viewport midpoint is within the behind budget`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // topIndex=0, viewportMidIndex=1, loaded=7, total=20 → gap=1 ≤ 3 → Hold
        val decision = mgr.decide(
            scrollY = 4_500,
            viewportChapterIndex = 1,
            window = uniformWindow(7),
            topIndex = 0,
            totalChapters = 20,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, decision)
    }

    @Test
    fun `shifts forward when viewport chapter gap exceeds budget`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // topIndex=0, viewportMidIndex=4, loaded=7, total=20 → gap=4 > 3 → ShiftForward
        val decision = mgr.decide(
            scrollY = 13_500,
            viewportChapterIndex = 4,
            window = uniformWindow(7),
            topIndex = 0,
            totalChapters = 20,
        )
        assertEquals(ChapterWindowManager.Decision.ShiftForward, decision)
    }

    @Test
    fun `shifts backward when scrollY is in the first half of the first chapter`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // scrollY=400 < 3000/2=1500, topIndex=3 > 0 → ShiftBackward
        val decision = mgr.decide(
            scrollY = 400,
            viewportChapterIndex = 3,
            window = uniformWindow(7),
            topIndex = 3,
            totalChapters = 20,
        )
        assertEquals(ChapterWindowManager.Decision.ShiftBackward, decision)
    }

    @Test
    fun `does not shift backward when at the very first chapter (topIndex=0)`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        val decision = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 0,
            window = uniformWindow(5),
            topIndex = 0,
            totalChapters = 10,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, decision)
    }

    @Test
    fun `does not shift forward when no more chapters exist beyond the window`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // loaded 5 chapters, topIndex=5, total=10 → topIndex+loaded=10=total → no more ahead
        val decision = mgr.decide(
            scrollY = 13_500,
            viewportChapterIndex = 9,
            window = uniformWindow(5),
            topIndex = 5,
            totalChapters = 10,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, decision)
    }

    @Test
    fun `holds for empty window`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        assertEquals(ChapterWindowManager.Decision.Hold, mgr.decide(0, 0, emptyList(), 0, 10))
    }

    // ── oscillation guard (regression for PRs #239 and #241) ────────────────

    @Test
    fun `forward shift does not immediately trigger backward on a short first chapter`() {
        // A "CHILDREN OF DUNE" divider page: very short chapter (~200px) as the new window top
        // after a forward shift. Without the guard, scrollY after removeTop compensation lands in
        // the first half of this chapter → ShiftBackward fires → oscillation.
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        val shortFirstChapter = 200
        val window = listOf(
            slot("ch4", 0,      shortFirstChapter),
            slot("ch5", 200,    3_000),
            slot("ch6", 3_200,  3_000),
            slot("ch7", 6_200,  3_000),
            slot("ch8", 9_200,  3_000),
            slot("ch9", 12_200, 3_000),
            slot("ch10",15_200, 3_000),
        )

        // Cycle 1: forward shift fires (viewportMidIndex=4 at topIndex=0, gap > 3)
        val d1 = mgr.decide(
            scrollY = 500,
            viewportChapterIndex = 4,
            window = window,
            topIndex = 0,
            totalChapters = 20,
        )
        assertEquals("should shift forward", ChapterWindowManager.Decision.ShiftForward, d1)

        // Cycle 2: after the shift topIndex advances to 1; the viewport mid is still at ch4
        // (user hasn't moved). Gap = 4-1 = 3 = budget → forward does NOT fire again.
        // But scrollY(50) < firstChapterHeight/2(100) → backward condition is met.
        // Without the guard it would fire ShiftBackward → oscillation.
        // With the guard it should Hold.
        val d2 = mgr.decide(
            scrollY = 50,
            viewportChapterIndex = 4,
            window = window,
            topIndex = 1,
            totalChapters = 20,
        )
        assertEquals("guard must suppress backward after forward", ChapterWindowManager.Decision.Hold, d2)
    }

    @Test
    fun `guard clears after one cycle, allowing genuine backward scroll`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        val window = uniformWindow(7)

        // Fire a forward shift to arm the guard.
        mgr.decide(scrollY = 13_500, viewportChapterIndex = 4, window = window, topIndex = 0, totalChapters = 20)

        // Guard cycle: suppresses backward.
        mgr.decide(scrollY = 50, viewportChapterIndex = 1, window = window, topIndex = 1, totalChapters = 20)

        // Third cycle: guard is gone. A genuine backward scroll (scrollY < firstChapterHeight/2)
        // should now fire ShiftBackward.
        val d3 = mgr.decide(
            scrollY = 200,
            viewportChapterIndex = 1,
            window = window,
            topIndex = 1,
            totalChapters = 20,
        )
        assertEquals("backward should fire once guard has cleared", ChapterWindowManager.Decision.ShiftBackward, d3)
    }

    // ── elided-view backward↔forward oscillation (chaptersBehind ≥ 2 required) ─────
    //
    // Field-observed 2026-07-21 (RIFFLE_DECO logs): elided view with heights `[499, 2809, 3631]`
    // and `chaptersBehind=1`. User scrolls backward, backward-shift fires (removeBottom + prepend
    // ch6). The prepend adds a placeholder ~viewport tall + scroll compensation → new scrollY
    // inside the tall prepended chapter, but viewport midpoint OVERSHOOTS the short middle
    // chapter (499 px) straight into ch8: viewportChapterIndex still 8, topIndex now 6, gap=2.
    // With `chaptersBehind=1`, `2 > 1` → forward shift fires → undoes the backward. Perpetual
    // oscillation, user cannot progress backward. Raising `chaptersBehind` to 2 absorbs a
    // single short middle chapter (gap=2 no longer > 2); raising to 3 absorbs two consecutive
    // short chapters (gap=3 no longer > 3). The elided reader uses 3.

    @Test
    fun `chaptersBehind 1 — backward shift into a short-middle-chapter window oscillates back to forward`() {
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        // Post-backward window: tall ch6 prepended over short ch7 over tall ch8. Scroll
        // compensation lands the user inside ch6 near ch7 → midY overshoots into ch8.
        val postBackwardWindow = listOf(
            slot("ch6", top = 0,     height = 2_337),
            slot("ch7", top = 2_337, height = 499),    // ← short middle
            slot("ch8", top = 2_836, height = 2_809),
        )
        val d = mgr.decide(
            scrollY = 2_448,
            viewportChapterIndex = 8,        // midY = 2448+1200 = 3648 → in ch8 slot [2836..5645)
            window = postBackwardWindow,
            topIndex = 6,
            totalChapters = 11,
            viewportHeight = 2_400,
        )
        assertEquals(
            "chaptersBehind=1: gap 2 > 1 → forward re-fires → oscillation",
            ChapterWindowManager.Decision.ShiftForward, d,
        )
    }

    @Test
    fun `chaptersBehind 3 — backward shift into a short-middle-chapter window sticks`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)   // elided view's value
        val postBackwardWindow = listOf(
            slot("ch6", top = 0,     height = 2_337),
            slot("ch7", top = 2_337, height = 499),
            slot("ch8", top = 2_836, height = 2_809),
        )
        val d = mgr.decide(
            scrollY = 2_448,
            viewportChapterIndex = 8,
            window = postBackwardWindow,
            topIndex = 6,
            totalChapters = 11,
            viewportHeight = 2_400,
        )
        assertEquals(
            "chaptersBehind=3: gap 2 > 3 is false → backward shift sticks, user can scroll back",
            ChapterWindowManager.Decision.Hold, d,
        )
    }

    @Test
    fun `chaptersBehind is mutable so the elided reader can raise it before openWindowAt`() {
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        // Verify the public setter propagates to the shift-decision path.
        mgr.chaptersBehind = 3
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 3,     // gap = 3-0 = 3, not > 3 with the raised threshold
            window = uniformWindow(5),
            topIndex = 0,
            totalChapters = 20,
            viewportHeight = 2_400,
        )
        assertEquals(
            "raised chaptersBehind must gate the forward trigger",
            ChapterWindowManager.Decision.Hold, d,
        )
    }

    @Test
    fun `reset clears the guard immediately`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        val window = uniformWindow(7)

        // Arm the guard via a forward shift.
        mgr.decide(scrollY = 13_500, viewportChapterIndex = 4, window = window, topIndex = 0, totalChapters = 20)

        // Reset as if the window was rebuilt (navigateTo).
        mgr.reset()

        // The guard is gone; backward should fire on the very next decide() call.
        val d = mgr.decide(
            scrollY = 200,
            viewportChapterIndex = 1,
            window = window,
            topIndex = 1,
            totalChapters = 20,
        )
        assertEquals("reset must clear guard", ChapterWindowManager.Decision.ShiftBackward, d)
    }

    // ── forward-only boundary ────────────────────────────────────────────────

    @Test
    fun `forward fires when gap is exactly budget+1`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // gap = viewportMidIndex(3+1=4) - topIndex(0) = 4 > 3
        val d = mgr.decide(
            scrollY = 10_500,
            viewportChapterIndex = 4,
            window = uniformWindow(7),
            topIndex = 0,
            totalChapters = 20,
        )
        assertEquals(ChapterWindowManager.Decision.ShiftForward, d)
    }

    @Test
    fun `forward does not fire when gap equals budget exactly`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        // gap = 3 - 0 = 3, not > 3 → Hold
        val d = mgr.decide(
            scrollY = 10_500,
            viewportChapterIndex = 3,
            window = uniformWindow(7),
            topIndex = 0,
            totalChapters = 20,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }

    // ── bottom-of-window trigger (short trailing chapter wall-off) ───────────
    //
    // Regression: elided-view continuous mode with a short trailing chapter walls off. Observed
    // fixture: 11-chapter book, window [ch5,ch6,ch7] heights=[3250,1263,499], viewport 2400.
    // Max scroll clamps at 2612 with midpoint at 3812 (inside ch6). Midpoint-only trigger gives
    // `gap = 6-5 = 1 ≤ chaptersBehind(1)` → Hold forever, even though ch8..ch10 are unloaded.
    // The `viewportHeight` overload propagates a bottom-of-window signal so decide() can fire a
    // forward shift when scroll is clamped and more chapters remain.

    @Test
    fun `short trailing chapter at bottom of window fires ShiftForward via viewport-height overload`() {
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch5", top = 0,    height = 3_250),
            slot("ch6", top = 3_250, height = 1_263),
            slot("ch7", top = 4_513, height = 499),   // ← short trailing; midY can't enter it
        )
        val d = mgr.decide(
            scrollY = 2_612,             // maxScroll = 5012 - 2400
            viewportChapterIndex = 6,     // midY falls in ch6
            window = window,
            topIndex = 5,
            totalChapters = 11,
            viewportHeight = 2_400,
        )
        assertEquals(
            "bottom-of-window trigger must unwedge the wall-off",
            ChapterWindowManager.Decision.ShiftForward, d,
        )
    }

    @Test
    fun `bottom-of-window trigger does not fire when loaded window fits inside the viewport`() {
        // Regression pin (2026-07-21 field repro after the initial wall-off fix): elided view
        // opened with 3 short chapters loaded whose total height (1500 px) is less than the
        // viewport (2400 px). Without the loadedContentBottom > viewportHeight guard,
        // atBottomOfLoadedWindow is true even at scrollY=0 → ShiftForward fires immediately →
        // topIndex advances → next decide() also fires → the window jumps [ch0..ch2] to
        // [ch3..ch5] with no user input. And once the pattern is established, any backward shift
        // is immediately re-undone by a fresh forward shift on the next decide, so the user
        // can't scroll back. Field-observed as "cannot scroll to the chapters BEFORE ch12".
        //
        // topIndex=0 pins out the backward-shift path (topIndex>0 is a required condition), so
        // the ONLY trigger this test exercises is the bottom-of-window one. Midpoint trigger is
        // false too (midY at 1200 in ch0 → viewportChapterIndex=0 → 0-0=0 not > 1). Decision
        // must be Hold; without the guard it was ShiftForward.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch0", top = 0,    height = 500),
            slot("ch1", top = 500,  height = 500),
            slot("ch2", top = 1_000, height = 500),   // total = 1500 < viewport (2400)
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 0,
            window = window,
            topIndex = 0,
            totalChapters = 11,
            viewportHeight = 2_400,
        )
        assertEquals(
            "loaded window fits in viewport — must not auto-cascade forward",
            ChapterWindowManager.Decision.Hold, d,
        )
    }

    @Test
    fun `bottom-of-window trigger holds when no more chapters exist ahead`() {
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch8", top = 0,    height = 3_250),
            slot("ch9", top = 3_250, height = 1_263),
            slot("ch10", top = 4_513, height = 499),
        )
        // scroll at bottom, but this IS the last chunk (topIndex+loaded=11=totalChapters).
        val d = mgr.decide(
            scrollY = 2_612,
            viewportChapterIndex = 9,
            window = window,
            topIndex = 8,
            totalChapters = 11,
            viewportHeight = 2_400,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }

    @Test
    fun `bottom-of-window trigger inactive when not at maxScroll — midpoint trigger governs`() {
        // scrollY inside the window (not clamped at max) AND above the backward-shift threshold
        // (firstChapterHeight/2 = 1625). The bottom-of-window trigger must NOT fire and neither
        // the backward nor the forward trigger applies → Hold.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch5", top = 0,    height = 3_250),
            slot("ch6", top = 3_250, height = 1_263),
            slot("ch7", top = 4_513, height = 499),
        )
        val d = mgr.decide(
            scrollY = 1_700,             // > firstChapterHeight/2 (no backward) and < maxScroll (2612)
            viewportChapterIndex = 5,     // midY sits inside ch5
            window = window,
            topIndex = 5,
            totalChapters = 11,
            viewportHeight = 2_400,       // scrollY+vH=4100 < loadedContentBottom=5012 → NOT at bottom
        )
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }

    // ── unknown viewport chapter (-1 from indexOfFirst) ──────────────────────

    @Test
    fun `unknown viewport chapter index (-1) does not trigger forward shift`() {
        val mgr = ChapterWindowManager(chaptersBehind = 3)
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = -1,
            window = uniformWindow(7),
            topIndex = 0,
            totalChapters = 20,
        )
        // -1 - 0 = -1, not > 3 → no forward; scrollY=0 < 1500 but topIndex=0 → no backward → Hold
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }
}
