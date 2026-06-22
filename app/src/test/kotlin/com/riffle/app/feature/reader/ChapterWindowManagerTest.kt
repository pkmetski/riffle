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
