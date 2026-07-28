package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkTitleBuilderTest {
    private fun timeline(vararg ch: AudiobookChapter) =
        AudiobookTimeline(durationSec = 10_000.0, chapters = ch.toList())

    @Test fun titledChapterUsesTitleAndOffset() {
        val t = timeline(
            AudiobookChapter(0, 0.0, 600.0, "Prologue"),
            AudiobookChapter(1, 600.0, 5000.0, "The Egg"),
        )
        // 600 + 12*60 + 45 = 1365s -> offset into "The Egg" = 765s = 12:45
        assertEquals("The Egg · 12:45", BookmarkTitleBuilder.defaultTitle(t, 1365.0))
    }

    @Test fun untitledChapterFallsBackToChapterNumber() {
        val t = timeline(
            AudiobookChapter(0, 0.0, 600.0, ""),
            AudiobookChapter(1, 600.0, 5000.0, "   "),
        )
        assertEquals("Chapter 2 · 12:45", BookmarkTitleBuilder.defaultTitle(t, 1365.0))
    }

    @Test fun noChaptersFallsBackToAbsoluteTimestamp() {
        val t = AudiobookTimeline(durationSec = 10_000.0, chapters = emptyList())
        assertEquals("1:02:11", BookmarkTitleBuilder.defaultTitle(t, 3731.0))
    }

    @Test fun offsetUnderTenMinutesStillTwoDigitSeconds() {
        val t = timeline(AudiobookChapter(0, 0.0, 5000.0, "One"))
        assertEquals("One · 3:05", BookmarkTitleBuilder.defaultTitle(t, 185.0))
    }
}
