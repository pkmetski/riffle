package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRailSegmentsTest {

    @Test
    fun `empty TOC produces single full-book segment with empty title`() {
        val segs = buildPdfRailSegments(emptyList(), totalPages = 50)
        assertEquals(1, segs.size)
        assertEquals("", segs[0].title)
        assertEquals(50f, segs[0].weight, 0.001f)
    }

    @Test
    fun `totalPages zero produces empty list`() {
        assertEquals(0, buildPdfRailSegments(listOf(PdfTocEntry("X", 0)), totalPages = 0).size)
    }

    @Test
    fun `each TOC entry maps to a segment and weights equal page counts`() {
        val toc = listOf(
            PdfTocEntry("Intro", 0),
            PdfTocEntry("Ch 1", 5),
            PdfTocEntry("Ch 2", 20),
        )
        val segs = buildPdfRailSegments(toc, totalPages = 30)
        assertEquals(3, segs.size)
        assertEquals("Intro", segs[0].title); assertEquals(5f, segs[0].weight, 0.001f)
        assertEquals("Ch 1", segs[1].title);  assertEquals(15f, segs[1].weight, 0.001f)
        assertEquals("Ch 2", segs[2].title);  assertEquals(10f, segs[2].weight, 0.001f)
    }

    @Test
    fun `out-of-order TOC entries are sorted by page before building segments`() {
        val toc = listOf(
            PdfTocEntry("Ch 2", 20),
            PdfTocEntry("Intro", 0),
            PdfTocEntry("Ch 1", 5),
        )
        val segs = buildPdfRailSegments(toc, totalPages = 30)
        assertEquals(listOf("Intro", "Ch 1", "Ch 2"), segs.map { it.title })
    }

    @Test
    fun `pages past EOF clamp into range`() {
        val toc = listOf(PdfTocEntry("Bad", 999))
        val segs = buildPdfRailSegments(toc, totalPages = 10)
        assertEquals(1, segs.size)
        // pageIndex clamped to 9; segment is [9, 10) → 1 page wide
        assertEquals(1f, segs[0].weight, 0.001f)
    }

    @Test
    fun `findActivePdfSegmentIndex picks the last segment whose start is at or before the page`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0), PdfTocEntry("B", 10), PdfTocEntry("C", 20)),
            totalPages = 30,
        )
        assertEquals(0, findActivePdfSegmentIndex(segs, 0))
        assertEquals(0, findActivePdfSegmentIndex(segs, 9))
        assertEquals(1, findActivePdfSegmentIndex(segs, 10))
        assertEquals(1, findActivePdfSegmentIndex(segs, 19))
        assertEquals(2, findActivePdfSegmentIndex(segs, 20))
        assertEquals(2, findActivePdfSegmentIndex(segs, 29))
        assertEquals(2, findActivePdfSegmentIndex(segs, 100)) // past end clamps
    }

    @Test
    fun `findActivePdfSegmentIndex returns -1 for empty segments`() {
        assertEquals(-1, findActivePdfSegmentIndex(emptyList(), 5))
    }

    @Test
    fun `pdfProgressionWithinActiveSegment is zero at segment start`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0), PdfTocEntry("B", 10)),
            totalPages = 20,
        )
        assertEquals(0f,
            pdfProgressionWithinActiveSegment(segs, activeIndex = 0,
                currentPageIndex = 0, intraPageOffset = 0f, totalPages = 20),
            0.001f)
        assertEquals(0f,
            pdfProgressionWithinActiveSegment(segs, activeIndex = 1,
                currentPageIndex = 10, intraPageOffset = 0f, totalPages = 20),
            0.001f)
    }

    @Test
    fun `pdfProgressionWithinActiveSegment hits 0_5 at segment midpoint`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0), PdfTocEntry("B", 10)),
            totalPages = 20,
        )
        // Segment A spans pages [0,10) — 10 pages. Page 5 is half-way (5/10).
        assertEquals(0.5f,
            pdfProgressionWithinActiveSegment(segs, activeIndex = 0,
                currentPageIndex = 5, intraPageOffset = 0f, totalPages = 20),
            0.001f)
    }

    @Test
    fun `pdfProgressionWithinActiveSegment is at most 1`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0), PdfTocEntry("B", 10)),
            totalPages = 20,
        )
        val p = pdfProgressionWithinActiveSegment(segs, activeIndex = 1,
            currentPageIndex = 19, intraPageOffset = 0.99f, totalPages = 20)
        assertTrue("progression must be in 0..1, was $p", p in 0f..1f)
    }

    @Test
    fun `intraPageOffset smoothly interpolates between integer pages`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0)),  // single segment spanning whole book
            totalPages = 10,
        )
        // page 4, offset 0.0 → 0.4; page 4, offset 0.5 → 0.45.
        val p0 = pdfProgressionWithinActiveSegment(
            segs, activeIndex = 0, currentPageIndex = 4, intraPageOffset = 0f,
            totalPages = 10,
        )
        val pHalf = pdfProgressionWithinActiveSegment(
            segs, activeIndex = 0, currentPageIndex = 4, intraPageOffset = 0.5f,
            totalPages = 10,
        )
        assertEquals(0.4f, p0, 0.001f)
        assertEquals(0.45f, pHalf, 0.001f)
    }

    @Test
    fun `rail cursor on early page of a short opening chapter stays near rail start`() {
        // Regression: the PDF rail used to feed the within-segment fraction directly to
        // ChapterNavigationRail.cursorPosition (which expects 0..1 over the WHOLE rail). That
        // made page 4 of a short opening chapter render the cursor ~80% across the entire bar.
        // The cursor must reflect global book progress (here ~4/300 ≈ 1.3%).
        val toc = listOf(
            PdfTocEntry("Preface", 0),
            PdfTocEntry("Ch 1", 5),
            PdfTocEntry("Ch 2", 50),
            PdfTocEntry("Ch 3", 150),
        )
        val segs = buildPdfRailSegments(toc, totalPages = 300)
        val zeroPage = 3
        val active = findActivePdfSegmentIndex(segs, zeroPage)
        val withinSeg = pdfProgressionWithinActiveSegment(
            segments = segs, activeIndex = active,
            currentPageIndex = zeroPage, intraPageOffset = 0f, totalPages = 300,
        )
        val cursor = weightedRailCursorPosition(active, segs, withinSeg)
        assertTrue("cursor on page 4 must be near rail start, was $cursor", cursor < 0.05f)
    }

    @Test
    fun `pdfProgressionWithinActiveSegment defends against out-of-range inputs`() {
        val segs = buildPdfRailSegments(
            listOf(PdfTocEntry("A", 0), PdfTocEntry("B", 10)),
            totalPages = 20,
        )
        // bogus activeIndex
        assertEquals(0f,
            pdfProgressionWithinActiveSegment(segs, activeIndex = 99,
                currentPageIndex = 5, intraPageOffset = 0f, totalPages = 20),
            0.001f)
        // empty segments
        assertEquals(0f,
            pdfProgressionWithinActiveSegment(emptyList(), 0, 5, 0f, 20),
            0.001f)
        // zero total pages
        assertEquals(0f,
            pdfProgressionWithinActiveSegment(segs, 0, 5, 0f, 0),
            0.001f)
    }
}
