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
    fun `does not shift backward when entire window fits in viewport`() {
        // Regression: book opens at fm02 (topIndex=1). All three chapters (fm01+fm02+title) are
        // tiny and fit in one viewport. scrollY=0 < firstChapterHeight/2 AND topIndex>0 would
        // normally trigger ShiftBackward, removing title and prepending cover. After that there
        // is no subsequent trigger (maxScrollY stays 0), so AppendOnly never fires and the reader
        // walls off. Suppressing ShiftBackward when fitsInViewport lets ShiftForward or AppendOnly
        // fire instead.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("fm01",  top = 0,   height = 220),
            slot("fm02",  top = 220, height = 140),
            slot("title", top = 360, height =  90),   // total = 450 << viewport (2400)
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 3,    // title is at spine idx 3
            window = window,
            topIndex = 1,
            totalChapters = 37,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        // ShiftBackward must be suppressed; ShiftForward fires (gap = 3-1=2 > chaptersBehind=1)
        assertEquals(
            "ShiftBackward must not fire when window fits in viewport",
            ChapterWindowManager.Decision.ShiftForward, d,
        )
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
        // atBottomOfLoadedWindow was true even at scrollY=0 → ShiftForward fired immediately →
        // topIndex advanced → the window jumped [ch0..ch2] to [ch3..ch5] with no user input,
        // and any backward shift was re-undone by a fresh forward shift on the next decide, so
        // the user couldn't scroll back. Field-observed as "cannot scroll to the chapters BEFORE
        // ch12".
        //
        // This test locks in the guard against ShiftForward for that case. When
        // appendOnlyMaxWindow is not passed (default 0), the fits-in-viewport branch also does
        // not fire — decision is Hold. See separate AppendOnly tests below for the opt-in path
        // that grows the window without dropping the top.
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

    // ── AppendOnly (fits-in-viewport wall-off unstick) ───────────────────────
    //
    // Regression: book e866cd1d ("12 Principles for Raising a Child with ADHD"). Spine opens
    // with cover → fm01 ("Praise for…" 2.3 KB) → fm02 ("Selected Works From…" 1.3 KB) →
    // title (0.6 KB) → copyright. The natural 3-chapter initial window (fm01, fm02, title) is
    // ~a few hundred pixels tall — fits inside a single viewport. ShiftForward can never fire
    // (loadedContentBottom <= viewportHeight is deliberately excluded upstream to avoid
    // cascading past the user's opened position). Result: scroll dead-ends between "Selected
    // Works From…" and the title page — "as if there is no more book". AppendOnly grows the
    // window without dropping the top so more chapters load AND the reader can still scroll
    // back to the front-matter.

    @Test
    fun `fires AppendOnly when loaded window fits in viewport and more chapters exist`() {
        // Fresh cold-open at spine[0] (topIndex=0 → backward-shift path pinned out because
        // topIndex>0 is a required condition).
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("cover", top = 0,   height = 180),
            slot("fm01",  top = 180, height = 220),
            slot("fm02",  top = 400, height = 140),  // total = 540 << viewport (2400)
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 0,     // opened at the cover
            window = window,
            topIndex = 0,
            totalChapters = 37,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(
            "fits-in-viewport wall-off must fire AppendOnly to load the next chapter",
            ChapterWindowManager.Decision.AppendOnly, d,
        )
    }

    @Test
    fun `AppendOnly is capped by appendOnlyMaxWindow`() {
        // Pathological "all short chapters" book: 8 tiny chapters loaded, still fit in viewport.
        // Cap prevents runaway loads that would eventually blow the WebView renderer memory.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = (0 until 8).map { i ->
            slot("ch$i", top = i * 100, height = 100)  // total = 800 < viewport (2400)
        }
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 1,
            window = window,
            topIndex = 0,
            totalChapters = 50,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(
            "AppendOnly must stop firing at the cap, even if window still fits in viewport",
            ChapterWindowManager.Decision.Hold, d,
        )
    }

    @Test
    fun `AppendOnly does not fire when no more chapters exist ahead`() {
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch0", top = 0,    height = 500),
            slot("ch1", top = 500,  height = 500),
            slot("ch2", top = 1_000, height = 500),
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 1,
            window = window,
            topIndex = 0,
            totalChapters = 3,           // window covers the whole spine
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }

    @Test
    fun `AppendOnly does not fire once loaded window exceeds viewport`() {
        // Once one appended chapter measures back to placeholder height (or the natural
        // combined height exceeds viewport), AppendOnly stops firing so the normal
        // ShiftForward/Backward algorithm takes over.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch0", top = 0,     height = 500),
            slot("ch1", top = 500,   height = 500),
            slot("ch2", top = 1_000, height = 500),
            slot("ch3", top = 1_500, height = 2_000),  // total = 3500 > viewport (2400)
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 1,
            window = window,
            topIndex = 0,
            totalChapters = 11,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, d)
    }

    @Test
    fun `fires AppendOnly again after appended chapter measures short`() {
        // Regression: AppendOnly appends chapter at placeholder height (~viewport), which
        // temporarily makes fitsInViewport=false and halts the chain. When it then measures back
        // to its actual short height, decide() must fire AppendOnly again so the window keeps
        // growing. This simulates the second decide() call after copyright.html (3 KB) measures
        // back down from placeholder to its real ~200 px height.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        // cover + fm01 + fm02 + title + copyright — all small, total 630 px << viewport 2400
        val window = listOf(
            slot("cover",      top = 0,   height = 120),
            slot("fm01",       top = 120, height = 180),
            slot("fm02",       top = 300, height = 130),
            slot("title",      top = 430, height =  80),
            slot("copyright",  top = 510, height = 120),  // just measured back from placeholder
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 0,
            window = window,
            topIndex = 0,
            totalChapters = 37,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(
            "AppendOnly must continue chaining when appended chapter also measures short",
            ChapterWindowManager.Decision.AppendOnly, d,
        )
    }

    @Test
    fun `ShiftForward wins over AppendOnly when both would apply`() {
        // Guard against a future refactor accidentally reordering the when-branches: ShiftForward
        // must take precedence so the memory-managed sliding window keeps working normally when
        // the midpoint has advanced past the behind budget, even if the fits-in-viewport
        // condition also happens to be true.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val window = listOf(
            slot("ch0", top = 0,   height = 300),
            slot("ch1", top = 300, height = 300),
            slot("ch2", top = 600, height = 300),   // total = 900 < viewport (2400) → fitsInViewport
        )
        val d = mgr.decide(
            scrollY = 0,
            viewportChapterIndex = 2,     // gap = 2 - 0 = 2 > chaptersBehind(1) → ShiftForward
            window = window,
            topIndex = 0,
            totalChapters = 11,
            viewportHeight = 2_400,
            appendOnlyMaxWindow = 8,
        )
        assertEquals(ChapterWindowManager.Decision.ShiftForward, d)
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

    // ── smooth-tail far-jump blank-screen regression ─────────────────────────
    //
    // Root cause: during a smooth-tail annotation navigation the OverScroller targets y (annotation
    // absolute scroll position). At scrollY=y the viewport midpoint is y + viewport/2. When the
    // annotation is in the last ~(viewport/2) px of a chapter, y + viewport/2 is inside the next
    // chapter, so ShiftForward fires at the animation's end. ShiftForward's removeTop scrollBy(-h)
    // was overwritten by the OverScroller on the next computeScroll frame, making the scroll land
    // viewport/2 + h past the annotation → placeholder blank territory.
    //
    // Fix: ContinuousWindowController.maybeShift calls port.abortFling() when smoothTailInProgress
    // after removeTop/prependChapter, so the OverScroller stops chasing the stale target. The abort
    // is guarded by smoothTailInProgress to avoid killing normal user flings at chapter boundaries.
    // This test pins the trigger: ShiftForward MUST fire when the smooth-tail endpoint places the
    // viewport midpoint in the following chapter.

    @Test
    fun `shift forward fires when smooth-tail endpoint puts viewport midpoint past the chapter boundary`() {
        // Window [ch20, ch21(target), ch22], chaptersBehind=1. Annotation at 90 % of ch21.
        // y = slot.top(ch21) + 0.9 * ch21.height = 5000 + 7200 = 12200.
        // At scrollY=y the midpoint = y + viewport/2 = 12200 + 1200 = 13400 → inside ch22
        // (which spans 13000..18000). Gap = ch22.globalIndex - topIndex = 21-19 = 2 > 1 → ShiftForward.
        // Without port.abortFling() in ContinuousWindowController.maybeShift the OverScroller
        // rewrites the removeTop scrollBy(-5000) correction on the next frame, landing 5000px past
        // the annotation in placeholder territory → blank screen.
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val viewport = 2400
        val ch20Height = 5000; val ch21Height = 8000; val ch22Height = 5000
        val window = listOf(
            slot("ch20",  0,                          ch20Height),
            slot("ch21",  ch20Height,                 ch21Height),
            slot("ch22",  ch20Height + ch21Height,    ch22Height),
        )
        val annotationOffsetInCh21 = (ch21Height * 0.9).toInt()  // 7200 px within ch21
        val scrollY = ch20Height + annotationOffsetInCh21         // 12200 — the smooth-tail target y
        val midpoint = scrollY + viewport / 2                     // 13400 — in ch22
        // ch22 starts at 13000; midpoint=13400 is inside ch22 (global index 21, topIndex=19 → gap 2)
        val viewportMidIndex = 21  // ch22's global index in the reading order
        val decision = mgr.decide(
            scrollY = scrollY,
            viewportChapterIndex = viewportMidIndex,
            window = window,
            topIndex = 19,
            totalChapters = 25,
            viewportHeight = viewport,
        )
        assertEquals(ChapterWindowManager.Decision.ShiftForward, decision)
    }

    @Test
    fun `holds when annotation at 80 pct of chapter keeps midpoint inside same chapter`() {
        // Symmetric check: with the annotation at 80 % the midpoint stays inside ch21 → Hold.
        // This distinguishes "the trigger fires only for annotations near the chapter end" from
        // "any mid-chapter annotation triggers a spurious shift".
        val mgr = ChapterWindowManager(chaptersBehind = 1)
        val viewport = 2400
        val ch20Height = 5000; val ch21Height = 8000; val ch22Height = 5000
        val window = listOf(
            slot("ch20",  0,                          ch20Height),
            slot("ch21",  ch20Height,                 ch21Height),
            slot("ch22",  ch20Height + ch21Height,    ch22Height),
        )
        val annotationOffsetInCh21 = (ch21Height * 0.8).toInt()  // 6400 px within ch21
        val scrollY = ch20Height + annotationOffsetInCh21         // 11400
        val midpoint = scrollY + viewport / 2                     // 12600 — still inside ch21 (5000..13000)
        val viewportMidIndex = 20  // ch21's global index — gap = 20-19 = 1 = chaptersBehind → not > budget
        val decision = mgr.decide(
            scrollY = scrollY,
            viewportChapterIndex = viewportMidIndex,
            window = window,
            topIndex = 19,
            totalChapters = 25,
            viewportHeight = viewport,
        )
        assertEquals(ChapterWindowManager.Decision.Hold, decision)
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
