package com.riffle.app.feature.reader

import com.riffle.core.domain.comic.ComicBookmark

// The CBZ rail-segment logic has moved to feature:reader (commonMain, shared with iOS). These
// forwarders keep the historical com.riffle.app.feature.reader entry points working unchanged.

internal fun cbzSegmentPageIndex(segment: RailSegment): Int =
    com.riffle.feature.reader.cbzSegmentPageIndex(segment)

fun buildCbzRailSegments(bookmarks: List<ComicBookmark>, pageCount: Int): List<RailSegment> =
    com.riffle.feature.reader.buildCbzRailSegments(bookmarks, pageCount)

fun findActiveCbzSegmentIndex(segments: List<RailSegment>, currentPage: Int): Int =
    com.riffle.feature.reader.findActiveCbzSegmentIndex(segments, currentPage)

fun cbzRailCursorPosition(
    segments: List<RailSegment>,
    activeIndex: Int,
    currentPage: Int,
    pageCount: Int,
): Float =
    com.riffle.feature.reader.cbzRailCursorPosition(segments, activeIndex, currentPage, pageCount)
