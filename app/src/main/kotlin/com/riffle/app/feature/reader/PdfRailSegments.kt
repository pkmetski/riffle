package com.riffle.app.feature.reader

// The PDF rail-segment logic has moved to feature:reader (commonMain, shared with iOS). These
// forwarders keep the historical com.riffle.app.feature.reader entry points working unchanged.

typealias PdfTocEntry = com.riffle.feature.reader.PdfTocEntry

internal fun pdfSegmentHref(pageIndex: Int): String =
    com.riffle.feature.reader.pdfSegmentHref(pageIndex)

fun buildPdfRailSegments(
    pdfTocEntries: List<PdfTocEntry>,
    totalPages: Int,
): List<RailSegment> =
    com.riffle.feature.reader.buildPdfRailSegments(pdfTocEntries, totalPages)

fun findActivePdfSegmentIndex(segments: List<RailSegment>, currentPageIndex: Int): Int =
    com.riffle.feature.reader.findActivePdfSegmentIndex(segments, currentPageIndex)

fun pdfProgressionWithinActiveSegment(
    segments: List<RailSegment>,
    activeIndex: Int,
    currentPageIndex: Int,
    intraPageOffset: Float = 0f,
    totalPages: Int,
): Float =
    com.riffle.feature.reader.pdfProgressionWithinActiveSegment(
        segments,
        activeIndex,
        currentPageIndex,
        intraPageOffset,
        totalPages,
    )
