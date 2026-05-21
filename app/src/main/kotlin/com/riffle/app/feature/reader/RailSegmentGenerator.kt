package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>): List<RailSegment> =
    tocEntries.map { RailSegment(it.title, it.href) }

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}
