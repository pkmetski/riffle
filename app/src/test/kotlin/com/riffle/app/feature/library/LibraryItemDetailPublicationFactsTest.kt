package com.riffle.app.feature.library

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
    fun `pdf page count is labelled by format`() {
        assertEquals("PDF · 321 pages", publicationPageCountText(EbookFormat.Pdf, 321))
    }

    @Test
    fun `zero or negative speed produces no estimate`() {
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = 0.0))
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = -1.0))
    }

    @Test
    fun `comic page count is labelled by format`() {
        assertEquals("Comic · 120 pages", publicationPageCountText(EbookFormat.Cbz, 120))
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
