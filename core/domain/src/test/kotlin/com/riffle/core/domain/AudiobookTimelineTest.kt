package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookTimelineTest {

    private fun chapters() = listOf(
        AudiobookChapter(0, 0.0, 100.0, "One"),
        AudiobookChapter(1, 100.0, 250.0, "Two"),
        AudiobookChapter(2, 250.0, 400.0, "Three"),
    )

    @Test
    fun `chapterAt returns the containing chapter`() {
        val t = AudiobookTimeline(400.0, chapters())
        assertEquals("One", t.chapterAt(0.0)?.title)
        assertEquals("Two", t.chapterAt(100.0)?.title)
        assertEquals("Two", t.chapterAt(249.9)?.title)
        assertEquals("Three", t.chapterAt(399.0)?.title)
    }

    @Test
    fun `a chapterless book has no current chapter and disables chapter nav`() {
        val t = AudiobookTimeline(400.0, emptyList())
        assertFalse(t.hasChapters)
        assertFalse(t.canNextChapter)
        assertFalse(t.canPreviousChapter)
        assertNull(t.chapterAt(120.0))
        assertNull(t.previousChapterTargetSec(120.0))
        assertNull(t.nextChapterTargetSec(120.0))
    }

    @Test
    fun `next chapter seeks to the following chapter start, null past the last`() {
        val t = AudiobookTimeline(400.0, chapters())
        assertEquals(100.0, t.nextChapterTargetSec(50.0)!!, 0.0)
        assertEquals(250.0, t.nextChapterTargetSec(120.0)!!, 0.0)
        assertNull(t.nextChapterTargetSec(300.0)) // already in the last chapter
    }

    @Test
    fun `previous chapter restarts the current chapter when a little way in`() {
        val t = AudiobookTimeline(400.0, chapters())
        // 120s is 20s into chapter Two (start 100) — past the restart threshold → restart Two.
        assertEquals(100.0, t.previousChapterTargetSec(120.0)!!, 0.0)
    }

    @Test
    fun `previous chapter jumps to the prior chapter when near the current start`() {
        val t = AudiobookTimeline(400.0, chapters())
        // 101s is 1s into chapter Two — within the threshold → jump to chapter One.
        assertEquals(0.0, t.previousChapterTargetSec(101.0)!!, 0.0)
    }

    @Test
    fun `previous chapter from the first chapter restarts at zero`() {
        val t = AudiobookTimeline(400.0, chapters())
        assertEquals(0.0, t.previousChapterTargetSec(1.0)!!, 0.0)
        assertTrue(t.hasChapters)
    }
}
