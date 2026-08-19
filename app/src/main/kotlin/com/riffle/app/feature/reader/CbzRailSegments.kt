package com.riffle.app.feature.reader

import com.riffle.core.domain.comic.ComicBookmark

internal fun cbzSegmentHref(pageIndex: Int): String = "cbz:page=$pageIndex"

internal fun cbzSegmentPageIndex(segment: RailSegment): Int =
    segment.href.removePrefix("cbz:page=").toIntOrNull() ?: 0

fun buildCbzRailSegments(bookmarks: List<ComicBookmark>, pageCount: Int): List<RailSegment> {
    if (pageCount <= 0) return emptyList()
    if (bookmarks.isEmpty()) {
        return listOf(RailSegment(title = "", href = cbzSegmentHref(0), weight = 1f))
    }
    val sorted = bookmarks
        .map { it.copy(pageIndex = it.pageIndex.coerceIn(0, pageCount - 1)) }
        .sortedBy { it.pageIndex }
    return sorted.mapIndexed { i, bookmark ->
        val nextStart = if (i + 1 < sorted.size) sorted[i + 1].pageIndex else pageCount
        val pages = (nextStart - bookmark.pageIndex).coerceAtLeast(1)
        RailSegment(
            title = bookmark.title,
            href = cbzSegmentHref(bookmark.pageIndex),
            weight = pages.toFloat() / pageCount,
            groupIndex = i,
        )
    }
}

fun findActiveCbzSegmentIndex(segments: List<RailSegment>, currentPage: Int): Int {
    if (segments.isEmpty()) return -1
    var active = 0
    for (i in segments.indices) {
        if (cbzSegmentPageIndex(segments[i]) <= currentPage) active = i else break
    }
    return active
}

fun cbzRailCursorPosition(
    segments: List<RailSegment>,
    activeIndex: Int,
    currentPage: Int,
    pageCount: Int,
): Float {
    if (segments.isEmpty() || pageCount <= 0) return 0f
    if (segments.size == 1) {
        return currentPage.toFloat() / (pageCount - 1).coerceAtLeast(1)
    }
    val startPage = cbzSegmentPageIndex(segments[activeIndex])
    val nextStart = if (activeIndex + 1 < segments.size) {
        cbzSegmentPageIndex(segments[activeIndex + 1])
    } else {
        pageCount
    }
    val pagesInSegment = (nextStart - startPage).coerceAtLeast(1)
    val pagesIntoSegment = (currentPage - startPage).coerceIn(0, pagesInSegment - 1)
    val progressionInSegment = pagesIntoSegment.toFloat() / (pagesInSegment - 1).coerceAtLeast(1)
    val priorWeight = segments.take(activeIndex).sumOf { it.weight.toDouble() }.toFloat()
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.001f)
    return ((priorWeight + segments[activeIndex].weight * progressionInSegment) / totalWeight)
        .coerceIn(0f, 1f)
}
