package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>): List<RailSegment> =
    tocEntries.flatMap { expandIfBlank(it) }

private fun expandIfBlank(entry: TocEntry): List<RailSegment> =
    if (entry.title.isBlank() && entry.children.isNotEmpty()) {
        entry.children.flatMap { expandIfBlank(it) }
    } else {
        listOf(RailSegment(entry.title, entry.href))
    }

fun findActiveSubdivisions(tocEntries: List<TocEntry>, currentHref: String?): List<RailSegment> {
    if (currentHref == null) return emptyList()
    val topLevel = tocEntries.flatMap { promoteIfBlank(it) }
    val active = topLevel.firstOrNull { it.containsHref(currentHref) } ?: return emptyList()
    return active.children.map { RailSegment(it.title, it.href) }
}

private fun promoteIfBlank(entry: TocEntry): List<TocEntry> =
    if (entry.title.isBlank() && entry.children.isNotEmpty()) {
        entry.children.flatMap { promoteIfBlank(it) }
    } else {
        listOf(entry)
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
