package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(1.0f, prog.coerceAtMost(1.0f), 0.001f)
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

    @Test
    fun `shiftNeeded — current index below topIndex triggers backward shift`() {
        // window covers chapters [2,3,4], topIndex=2
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.BACKWARD,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 1, topIndex = 2, readingOrderSize = 5)
        )
    }

    @Test
    fun `shiftNeeded — current index above topIndex+2 triggers forward shift`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.FORWARD,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 0, readingOrderSize = 5)
        )
    }

    @Test
    fun `shiftNeeded — current within window returns NONE`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 3, topIndex = 2, readingOrderSize = 5)
        )
    }

    @Test
    fun `shiftNeeded NONE when at first chapter and cannot go backward`() {
        assertEquals(
            ContinuousPositionTracker.ShiftDirection.NONE,
            ContinuousPositionTracker.shiftNeeded(currentChapterIndex = 0, topIndex = 0, readingOrderSize = 3)
        )
    }
}
