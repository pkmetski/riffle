package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, currentHref: String): List<RailSegment> {
    val currentBase = currentHref.substringBefore('#')
    val chapter = tocEntries.find { it.href.substringBefore('#') == currentBase }
        ?: return emptyList()
    return if (chapter.children.isEmpty()) {
        listOf(RailSegment(chapter.title, chapter.href))
    } else {
        chapter.children.map { RailSegment(it.title, it.href) }
    }
}

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}
