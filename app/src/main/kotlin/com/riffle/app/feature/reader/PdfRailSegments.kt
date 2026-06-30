package com.riffle.app.feature.reader

/**
 * One node of a PDF outline used to build chapter-rail segments. Flat —
 * the rail flattens nested TOC sub-chapters into top-level groupings (same
 * convention EPUB uses, where the rail shows top-level spine items, not
 * inner anchors).
 *
 * @property title  Chapter / section title, as the PDF outline names it.
 * @property pageIndex  0-based page index where this chapter begins.
 */
data class PdfTocEntry(val title: String, val pageIndex: Int)

/**
 * Synthetic href used as the stable identity for a PDF rail segment.
 * The existing href-based rail UI (active-segment lookup, hit-testing)
 * operates on these tokens opaquely; nothing else parses them.
 */
internal fun pdfSegmentHref(pageIndex: Int): String = "page=$pageIndex"

/**
 * Build rail segments from a PDF outline. Each top-level TOC node becomes
 * one segment running `[startPage, nextStartPage)`; the trailing segment
 * extends to [totalPages]. Segment weight = page count of its range, so the
 * rail visually mirrors chapter lengths.
 *
 * If the outline is empty (common for scanned/converted PDFs without
 * embedded TOCs), returns a single full-book segment with no label — same
 * fallback EPUB uses when its publication has no TOC. The cursor still
 * shows page progress, which is the rail's primary utility.
 */
fun buildPdfRailSegments(
    pdfTocEntries: List<PdfTocEntry>,
    totalPages: Int,
): List<RailSegment> {
    if (totalPages <= 0) return emptyList()
    if (pdfTocEntries.isEmpty()) {
        return listOf(RailSegment(title = "", href = pdfSegmentHref(0), weight = totalPages.toFloat()))
    }
    // Ensure entries are sorted by page; allow duplicates and out-of-order
    // sources without crashing. Clamp into [0, totalPages-1] to handle PDFs
    // with malformed outlines pointing past EOF.
    val normalized = pdfTocEntries
        .map { it.copy(pageIndex = it.pageIndex.coerceIn(0, totalPages - 1)) }
        .sortedBy { it.pageIndex }
    val segments = ArrayList<RailSegment>(normalized.size)
    for (i in normalized.indices) {
        val start = normalized[i].pageIndex
        val end = if (i + 1 < normalized.size) normalized[i + 1].pageIndex else totalPages
        val pages = (end - start).coerceAtLeast(1)
        segments += RailSegment(
            title = normalized[i].title,
            href = pdfSegmentHref(start),
            weight = pages.toFloat(),
        )
    }
    return segments
}

/**
 * Index of the segment that owns [currentPageIndex] (0-based). Returns 0 if
 * the page is before every segment, the last segment if past the last
 * segment's start, or -1 if [segments] is empty.
 */
fun findActivePdfSegmentIndex(segments: List<RailSegment>, currentPageIndex: Int): Int {
    if (segments.isEmpty()) return -1
    val starts = segments.map { segmentPageIndex(it) }
    // Walk forward to find the last segment whose start is <= currentPageIndex.
    var active = 0
    for (i in starts.indices) {
        if (starts[i] <= currentPageIndex) active = i else break
    }
    return active
}

/**
 * Progression (0..1) within [segments]\[activeIndex\] for the cursor's
 * horizontal position on the rail. Computed from the page distance traveled
 * inside the active segment plus an optional intra-page offset (0..1) for
 * smooth interpolation during scroll-mode reading.
 *
 * Returns 0f for an empty or invalid input.
 */
fun pdfProgressionWithinActiveSegment(
    segments: List<RailSegment>,
    activeIndex: Int,
    currentPageIndex: Int,
    intraPageOffset: Float = 0f,
    totalPages: Int,
): Float {
    if (segments.isEmpty() || activeIndex !in segments.indices || totalPages <= 0) return 0f
    val startPage = segmentPageIndex(segments[activeIndex])
    val endPage = if (activeIndex + 1 < segments.size) {
        segmentPageIndex(segments[activeIndex + 1])
    } else {
        totalPages
    }
    val pagesInSegment = (endPage - startPage).coerceAtLeast(1)
    val pagesIntoSegment = (currentPageIndex - startPage).coerceIn(0, pagesInSegment - 1)
    val fraction = (pagesIntoSegment + intraPageOffset.coerceIn(0f, 1f)) / pagesInSegment.toFloat()
    return fraction.coerceIn(0f, 1f)
}

/** Decode the page index back out of a synthetic href written by [pdfSegmentHref]. */
private fun segmentPageIndex(segment: RailSegment): Int =
    segment.href.removePrefix("page=").toIntOrNull() ?: 0
