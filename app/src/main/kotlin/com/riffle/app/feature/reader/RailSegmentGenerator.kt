package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry

// The rail-segment logic has moved to feature:reader (commonMain, shared with iOS). These
// forwarders keep the historical com.riffle.app.feature.reader entry points working unchanged.

fun buildRailSegments(
    tocEntries: List<TocEntry>,
    bookTitle: String = "",
    spineHrefs: List<String> = emptyList(),
    positionCounts: List<Int> = emptyList(),
): List<RailSegment> =
    com.riffle.feature.reader.buildRailSegments(tocEntries, bookTitle, spineHrefs, positionCounts)

fun findActiveSegmentIndex(
    segments: List<RailSegment>,
    currentHref: String,
    spineHrefs: List<String> = emptyList(),
    progression: Float? = null,
): Int =
    com.riffle.feature.reader.findActiveSegmentIndex(segments, currentHref, spineHrefs, progression)

fun progressionWithinRailSegment(
    segments: List<RailSegment>,
    currentHref: String,
    activeIndex: Int,
    progression: Float,
): Float =
    com.riffle.feature.reader.progressionWithinRailSegment(segments, currentHref, activeIndex, progression)

fun weightSegmentsByChapterLength(
    segments: List<RailSegment>,
    spineHrefs: List<String>,
    positionCounts: List<Int>,
): List<RailSegment> =
    com.riffle.feature.reader.weightSegmentsByChapterLength(segments, spineHrefs, positionCounts)

fun railSegmentBounds(segments: List<RailSegment>, totalWidth: Float): List<Pair<Float, Float>> =
    com.riffle.feature.reader.railSegmentBounds(segments, totalWidth)

fun weightedRailCursorPosition(
    activeIndex: Int,
    segments: List<RailSegment>,
    progression: Float,
): Float =
    com.riffle.feature.reader.weightedRailCursorPosition(activeIndex, segments, progression)

fun bookmarkRailPosition(
    segments: List<RailSegment>,
    chapterHref: String,
    progression: Double,
    spineHrefs: List<String> = emptyList(),
): Float? =
    com.riffle.feature.reader.bookmarkRailPosition(segments, chapterHref, progression, spineHrefs)

fun railSegmentIndexAt(segments: List<RailSegment>, x: Float, totalWidth: Float): Int =
    com.riffle.feature.reader.railSegmentIndexAt(segments, x, totalWidth)
