package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, currentHref: String?): List<RailSegment> =
    tocEntries.flatMap { entry -> expandEntry(entry, currentHref) }

private fun expandEntry(entry: TocEntry, currentHref: String?): List<RailSegment> {
    if (entry.title.isBlank() && entry.children.isNotEmpty()) {
        return entry.children.flatMap { expandEntry(it, currentHref) }
    }
    if (entry.children.isEmpty() || currentHref == null) {
        return listOf(RailSegment(entry.title, entry.href))
    }
    if (entry.containsHref(currentHref)) {
        return entry.children.flatMap { expandEntry(it, currentHref) }
    }
    return listOf(RailSegment(entry.title, entry.href))
}

private fun TocEntry.containsHref(href: String): Boolean {
    if (this.href == href) return true
    if (this.href.substringBefore('#') == href.substringBefore('#')) return true
    return children.any { it.containsHref(href) }
}

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}
