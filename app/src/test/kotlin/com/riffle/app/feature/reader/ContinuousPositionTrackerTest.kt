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

    @Test
    fun `forwardShiftNeeded — viewport bottom in last chapter triggers FORWARD`() {
        // window [0,1,2], viewport bottom just entered ch2 (last chapter)
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportBottomChapterIndex = 2, topIndex = 0, readingOrderSize = 5
        ))
    }

    @Test
    fun `forwardShiftNeeded — viewport bottom in middle chapter returns false`() {
        // window [0,1,2], viewport bottom in ch1 (middle)
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportBottomChapterIndex = 1, topIndex = 0, readingOrderSize = 5
        ))
    }

    @Test
    fun `forwardShiftNeeded — no more chapters beyond window returns false`() {
        // window [3,4,5] with readingOrderSize=6 — ch6 doesn't exist
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportBottomChapterIndex = 5, topIndex = 3, readingOrderSize = 6
        ))
    }

    @Test
    fun `forwardShiftNeeded — viewport bottom already past last chapter (short chapter)`() {
        // window [0,1,2], viewport bottom resolves to ch2 even when viewport is larger than ch2
        assertTrue(ContinuousPositionTracker.forwardShiftNeeded(
            viewportBottomChapterIndex = 2, topIndex = 0, readingOrderSize = 10
        ))
    }

    @Test
    fun `forwardShiftNeeded — after FORWARD shift viewport bottom in new middle chapter is false`() {
        // After FORWARD shift topIdx=0→1; window=[ch1,ch2,ch3]; viewport bottom in ch2 (middle)
        assertFalse(ContinuousPositionTracker.forwardShiftNeeded(
            viewportBottomChapterIndex = 2, topIndex = 1, readingOrderSize = 10
        ))
    }
}
