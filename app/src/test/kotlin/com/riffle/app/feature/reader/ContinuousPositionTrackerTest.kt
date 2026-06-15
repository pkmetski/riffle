package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPositionTrackerTest {

    // Window: chapter A = height 1000, top 0; chapter B = height 500, top 1000
    private val window = listOf(
        ContinuousPositionTracker.ChapterSlot("A.xhtml", top = 0, height = 1000),
        ContinuousPositionTracker.ChapterSlot("B.xhtml", top = 1000, height = 500),
    )

    @Test
    fun `scrollY 0 with viewport 800 — midY 400 is in chapter A, progression 0_4`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 0, viewportHeight = 800, window = window
        )
        assertEquals("A.xhtml", href)
        assertEquals(0.4f, prog, 0.001f)
    }

    @Test
    fun `scrollY 1000 with viewport 800 — midY 1400 is in chapter B, progression 0_8`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 1000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(0.8f, prog, 0.001f)
    }

    @Test
    fun `scrollY past all chapters — clamps to last chapter`() {
        val (href, prog) = ContinuousPositionTracker.locatorAt(
            scrollY = 5000, viewportHeight = 800, window = window
        )
        assertEquals("B.xhtml", href)
        assertEquals(1.0f, prog, 0.001f)
    }

    @Test
    fun `scrollOffsetFor returns correct offset for chapter B at progression 0_5`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "B.xhtml", progression = 0.5f, window = window
        )
        // top=1000 + 0.5*500 = 1250
        assertEquals(1250, offset)
    }

    @Test
    fun `scrollOffsetFor returns null when chapter not in window`() {
        val offset = ContinuousPositionTracker.scrollOffsetFor(
            href = "C.xhtml", progression = 0.0f, window = window
        )
        assertNull(offset)
    }

    // ── forwardShiftNeeded ────────────────────────────────────────────────────

    // Window of 5: 1 behind + current + 3 ahead (chaptersBehind = 1).

    @Test
    fun `forwardShiftNeeded — midpoint past the behind budget triggers FORWARD`() {
        // window [0..4], chaptersBehind=1; midpoint in ch2 is >1 slot past top → shift
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — midpoint within the behind budget returns false`() {
        // window [0..4], midpoint in ch1 == topIndex+chaptersBehind → no shift yet
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 1, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — no more chapters beyond window returns false`() {
        // window covers [15..19], readingOrderSize=20 — nothing left to append
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 18, topIndex = 15, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — after FORWARD shift midpoint sits at behind budget so false`() {
        // After a shift topIndex advances by 1 but the midpoint chapter is unchanged, so the
        // midpoint is back at topIndex+chaptersBehind → condition clears (no oscillation).
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 1, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }

    @Test
    fun `forwardShiftNeeded — fires while several chapters remain loaded ahead (lead time)`() {
        // window [0..4], midpoint in ch2 → shift fires even though ch3,ch4 are still loaded
        // ahead. This is the look-ahead lead time that prevents blank gaps at chapter seams.
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportChapterIndex = 2, topIndex = 0, loadedChapterCount = 5,
            readingOrderSize = 20, chaptersBehind = 1,
        ))
    }
}
