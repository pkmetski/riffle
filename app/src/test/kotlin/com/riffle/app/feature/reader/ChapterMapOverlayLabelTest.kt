package com.riffle.app.feature.reader

import com.riffle.core.common.TimeRemaining
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterMapOverlayLabelTest {

    private val spanishTemplates = ChapterMapProgressLabelTemplates(
        chapterCount = "Capítulo %1\$d de %2\$d",
        durationMinutes = "%1\$d min",
        durationHoursMinutes = "%1\$d h %2\$d min",
        chapterRemainingExact = "%1\$s capítulo",
        chapterRemainingEstimated = "~%1\$s capítulo",
        chapterRemainingLessThanMinute = "< 1 min capítulo",
        bookRemainingExact = "%1\$s total",
        bookRemainingEstimated = "~%1\$s total",
        bookRemainingLessThanMinute = "< 1 min total",
    )

    @Test
    fun `chapter count label uses localized template`() {
        assertEquals("Capítulo 2 de 7", formatChapterCount(1, 7, spanishTemplates))
        assertEquals("Capítulo 7 de 7", formatChapterCount(12, 7, spanishTemplates))
    }

    @Test
    fun `estimated time labels use localized duration and suffix templates`() {
        assertEquals("1 h 5 min", formatDuration(3_900, spanishTemplates))
        assertEquals("~1 h 5 min capítulo", formatChapterRemaining(TimeRemaining.Estimated(3_900), spanishTemplates))
        assertEquals("~2 h 5 min total", formatBookRemaining(TimeRemaining.Estimated(7_500), spanishTemplates))
        assertEquals("< 1 min capítulo", formatChapterRemaining(TimeRemaining.Estimated(59), spanishTemplates))
        assertEquals("< 1 min total", formatBookRemaining(TimeRemaining.Estimated(59), spanishTemplates))
    }

    @Test
    fun `exact time labels use localized suffix templates`() {
        assertEquals("1:02:03 capítulo", formatChapterRemaining(TimeRemaining.Exact(3_723), spanishTemplates))
        assertEquals("12:05 total", formatBookRemaining(TimeRemaining.Exact(725), spanishTemplates))
    }
}
