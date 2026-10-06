package com.riffle.feature.library.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryItemDetailHelpersTest {

    // formatCompactDuration -------------------------------------------------------

    @Test
    fun formatCompactDurationHoursAndMinutes() {
        assertEquals("1h 30m", formatCompactDuration(5400.0))
    }

    @Test
    fun formatCompactDurationHoursOnly() {
        assertEquals("2h", formatCompactDuration(7200.0))
    }

    @Test
    fun formatCompactDurationMinutesOnly() {
        assertEquals("45m", formatCompactDuration(2700.0))
    }

    @Test
    fun formatCompactDurationZero() {
        assertEquals("0m", formatCompactDuration(0.0))
    }

    @Test
    fun formatCompactDurationLessThanOneMinute() {
        assertEquals("0m", formatCompactDuration(59.0))
    }

    // ebookReadingTimeText --------------------------------------------------------

    @Test
    fun ebookReadingTimeTextNotStarted() {
        val result = ebookReadingTimeText(
            totalSec = 3600L,
            readingProgress = 0f,
            estimated = { "EST:$it" },
        )
        assertEquals("EST:1h", result)
    }

    @Test
    fun ebookReadingTimeTextInProgress() {
        val result = ebookReadingTimeText(
            totalSec = 3600L,
            readingProgress = 0.5f,
            estimatedTotal = { "TOTAL:$it" },
            estimatedTotalRemaining = { total, rem -> "TOTAL:$total REM:$rem" },
        )
        assertEquals("TOTAL:1h REM:30m", result)
    }

    @Test
    fun ebookReadingTimeTextFinished() {
        val result = ebookReadingTimeText(
            totalSec = 7200L,
            readingProgress = 1.0f,
            estimatedTotal = { "TOTAL:$it" },
        )
        assertEquals("TOTAL:2h", result)
    }

    @Test
    fun ebookReadingTimeTextNearlyFinishedTreatedAsFinished() {
        val result = ebookReadingTimeText(
            totalSec = 3600L,
            readingProgress = 0.99f,
            estimatedTotal = { "TOTAL:$it" },
        )
        assertEquals("TOTAL:1h", result)
    }

    // pageCountText ---------------------------------------------------------------

    @Test
    fun pageCountTextNotStarted() {
        val result = pageCountText(
            pages = 300,
            readingProgress = 0f,
            formatPages = { "$it pages" },
        )
        assertEquals("300 pages", result)
    }

    @Test
    fun pageCountTextInProgress() {
        val result = pageCountText(
            pages = 300,
            readingProgress = 0.5f,
            formatPages = { "$it pages" },
            formatPagesRead = { read, total -> "$read of $total read" },
        )
        assertEquals("150 of 300 read", result)
    }

    @Test
    fun pageCountTextEmptyForZeroPages() {
        val result = pageCountText(pages = 0, readingProgress = 0f)
        assertEquals("", result)
    }

    @Test
    fun pageCountTextClampsToAtLeastOnePage() {
        val result = pageCountText(
            pages = 100,
            readingProgress = 0.001f,
            formatPagesRead = { read, total -> "$read/$total" },
        )
        assertEquals("1/100", result)
    }

    // stripHtmlTagsToText ---------------------------------------------------------

    @Test
    fun stripHtmlTagsRemovesTags() {
        val result = stripHtmlTagsToText("<b>Hello</b> <i>world</i>")
        assertEquals("Hello world", result)
    }

    @Test
    fun stripHtmlTagsDecodesNamedEntities() {
        val result = stripHtmlTagsToText("A &amp; B &lt;C&gt; D")
        assertEquals("A & B <C> D", result)
    }

    @Test
    fun stripHtmlTagsCollapsesWhitespace() {
        val result = stripHtmlTagsToText("   hello   world   ")
        assertEquals("hello world", result)
    }

    @Test
    fun stripHtmlTagsHandlesNumericEntities() {
        val result = stripHtmlTagsToText("&#39;quoted&#39;")
        assertEquals("'quoted'", result)
    }
}
