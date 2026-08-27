package com.riffle.app.feature.library

import com.riffle.app.feature.audiobook.CompactDurationLabelTemplates
import com.riffle.core.models.EbookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryItemDetailPublicationFactsTest {

    @Test
    fun `personalized estimate multiplies Readium positions by historical speed`() {
        assertEquals(7_560L, estimatedReadingTimeSec(totalPositions = 120, secPerPosition = 63.0))
    }

    @Test
    fun `invalid estimate inputs do not surface a misleading duration`() {
        assertNull(estimatedReadingTimeSec(totalPositions = 0, secPerPosition = 63.0))
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = Double.NaN))
    }

    @Test
    fun `fresh epub shows estimated total reading time`() {
        assertEquals("2h 6m estimated", ebookReadingTimeText(7_560L, 0f))
    }

    @Test
    fun `in-progress epub shows estimated total and remaining time`() {
        assertEquals(
            "2h 6m estimated total · 1h 3m remaining",
            ebookReadingTimeText(7_560L, 0.5f),
        )
    }

    @Test
    fun `finished epub omits remaining time`() {
        assertEquals("2h 6m estimated total", ebookReadingTimeText(7_560L, 1f))
    }

    @Test
    fun `fresh audiobook duration line uses localized compact duration labels`() {
        val labels = CompactDurationLabelTemplates(
            minutes = "%1\$d мин",
            hours = "%1\$d ч",
            hoursMinutes = "%1\$d ч %2\$d мин",
        )

        assertEquals(
            "Аудиокнига · 10 ч 53 мин",
            audiobookDurationLineText(
                durationSec = 10 * 3600.0 + 53 * 60,
                readingProgress = 0f,
                durationLabels = labels,
                audiobookDuration = { duration -> "Аудиокнига · $duration" },
            ),
        )
    }

    @Test
    fun `in-progress audiobook duration line localizes total and remaining labels`() {
        val labels = CompactDurationLabelTemplates(
            minutes = "%1\$d мин",
            hours = "%1\$d ч",
            hoursMinutes = "%1\$d ч %2\$d мин",
        )

        assertEquals(
            "10 ч 53 мин общо · остава 5 ч 26 мин",
            audiobookDurationLineText(
                durationSec = 10 * 3600.0 + 53 * 60,
                readingProgress = 0.5f,
                durationLabels = labels,
                durationTotalRemaining = { total, remaining -> "$total общо · остава $remaining" },
            ),
        )
    }

    @Test
    fun `fresh fixed-page item shows total page count without format prefix`() {
        assertEquals("321 pages", publicationPageCountText(pageCount = 321, readingProgress = 0f))
    }

    @Test
    fun `invalid fixed-page progress falls back to total page count`() {
        assertEquals("321 pages", publicationPageCountText(pageCount = 321, readingProgress = Float.NaN))
    }

    @Test
    fun `zero or negative speed produces no estimate`() {
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = 0.0))
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = -1.0))
    }

    @Test
    fun `in-progress fixed-page item shows pages read out of total`() {
        assertEquals(
            "40 of 120 pages read",
            publicationPageCountText(pageCount = 120, readingProgress = 40f / 120f),
        )
    }

    @Test
    fun `finished fixed-page item shows all pages read`() {
        assertEquals("120 of 120 pages read", publicationPageCountText(pageCount = 120, readingProgress = 1f))
    }

    @Test
    fun `facts line reserves space for async formats so the action row never reflows`() {
        // EPUB estimates and extracted PDF page counts arrive seconds after first render;
        // the line must hold its slot from the first frame (tap-target stability).
        assertTrue(publicationFactsLineReservesSpace(EbookFormat.Epub))
        assertTrue(publicationFactsLineReservesSpace(EbookFormat.Pdf))
    }

    @Test
    fun `facts line does not reserve space for synchronous formats`() {
        assertFalse(publicationFactsLineReservesSpace(EbookFormat.Cbz))
        assertFalse(publicationFactsLineReservesSpace(EbookFormat.Unsupported))
    }
}
