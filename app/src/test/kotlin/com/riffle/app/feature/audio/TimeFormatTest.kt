package com.riffle.app.feature.audio

import com.riffle.core.domain.AudiobookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun `remaining under a minute reads as less than a minute`() {
        assertEquals("Less than a minute left", formatRemainingReadable(0.0))
        assertEquals("Less than a minute left", formatRemainingReadable(45.0))
    }

    @Test fun `remaining under an hour is m only`() {
        assertEquals("1m left", formatRemainingReadable(60.0))
        assertEquals("45m left", formatRemainingReadable(45 * 60.0))
        assertEquals("59m left", formatRemainingReadable(59 * 60.0 + 59))
    }

    @Test fun `remaining at least an hour includes hours and minutes`() {
        assertEquals("1h 0m left", formatRemainingReadable(60 * 60.0))
        assertEquals("3h 12m left", formatRemainingReadable(3 * 3600.0 + 12 * 60.0 + 30))
        assertEquals("12h 5m left", formatRemainingReadable(12 * 3600.0 + 5 * 60.0))
    }

    @Test fun `negative input clamps to zero`() {
        assertEquals("Less than a minute left", formatRemainingReadable(-42.0))
    }

    @Test fun `notification artist includes chapter title when chapter is present`() {
        val chapter = AudiobookChapter(index = 2, startSec = 0.0, endSec = 100.0, title = "Part Two")
        assertEquals("Part Two · 3h 12m left", notificationArtistText(chapter, 3 * 3600.0 + 12 * 60.0 + 30))
    }

    @Test fun `notification artist is just remaining time when no chapter`() {
        assertEquals("45m left", notificationArtistText(null, 45 * 60.0))
    }
}
