package com.riffle.feature.library.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.riffle.core.models.TocEntry
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

    // htmlBlurb -------------------------------------------------------------------

    @Test
    fun htmlBlurbRemovesTags() {
        assertEquals("Hello world", htmlBlurb("<b>Hello</b> <i>world</i>").text)
    }

    @Test
    fun htmlBlurbPreservesBoldSpan() {
        val result = htmlBlurb("<b>Hello</b>")
        assertEquals("Hello", result.text)
        val spans = result.spanStyles
        assertEquals(1, spans.size)
        assertEquals(FontWeight.Bold, spans[0].item.fontWeight)
    }

    @Test
    fun htmlBlurbPreservesItalicSpan() {
        val result = htmlBlurb("<i>Hello</i>")
        assertEquals("Hello", result.text)
        val spans = result.spanStyles
        assertEquals(1, spans.size)
        assertEquals(FontStyle.Italic, spans[0].item.fontStyle)
    }

    @Test
    fun htmlBlurbDecodesNamedEntities() {
        assertEquals("A & B <C> D", htmlBlurb("A &amp; B &lt;C&gt; D").text)
    }

    @Test
    fun htmlBlurbHandlesNumericEntities() {
        assertEquals("'quoted'", htmlBlurb("&#39;quoted&#39;").text)
    }

    @Test
    fun htmlBlurbCollapsesWhitespace() {
        assertEquals("Hello world", htmlBlurb("Hello   world").text.trim())
    }

    // Lone-surrogate handling (&#xD800; etc.) is Ksoup-implementation-defined; not tested.

    // topLevelTocCount ------------------------------------------------------------

    @Test
    fun topLevelTocCountCountsOnlyTopLevelEntries() {
        val toc = listOf(
            TocEntry(
                title = "Chapter 1",
                href = "c1",
                children = listOf(
                    TocEntry(title = "1.1", href = "c1-1"),
                    TocEntry(title = "1.2", href = "c1-2", children = listOf(TocEntry(title = "1.2.1", href = "c1-2-1"))),
                ),
            ),
            TocEntry(title = "Chapter 2", href = "c2", children = listOf(TocEntry(title = "2.1", href = "c2-1"))),
            TocEntry(title = "Chapter 3", href = "c3"),
        )

        assertEquals(3, topLevelTocCount(toc))
    }

    @Test
    fun topLevelTocCountIgnoresNestingDepth() {
        val flat = listOf(TocEntry("A", "a"), TocEntry("B", "b"))
        val nested = listOf(
            TocEntry("A", "a", children = List(50) { TocEntry("A$it", "a$it") }),
            TocEntry("B", "b", children = List(50) { TocEntry("B$it", "b$it") }),
        )

        assertEquals(topLevelTocCount(flat), topLevelTocCount(nested))
    }

    @Test
    fun topLevelTocCountEmptyIsZero() {
        assertEquals(0, topLevelTocCount(emptyList()))
    }
}
