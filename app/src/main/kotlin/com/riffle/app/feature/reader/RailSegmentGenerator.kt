package com.riffle.app.feature.reader

fun buildRailSegments(tocEntries: List<TocEntry>, bookTitle: String = ""): List<RailSegment> {
    val bookTitleNorm = bookTitle.normalize()
    return tocEntries.flatMap { expandIfRedundant(it, bookTitleNorm) }
}

private fun expandIfRedundant(entry: TocEntry, bookTitleNorm: String): List<RailSegment> {
    val isRedundantContainer = entry.children.isNotEmpty() && (
        entry.title.isBlank() ||
            (bookTitleNorm.isNotEmpty() && entry.title.normalize() == bookTitleNorm)
        )
    return if (isRedundantContainer) {
        entry.children.flatMap { expandIfRedundant(it, bookTitleNorm) }
    } else {
        listOf(RailSegment(entry.title, entry.href))
    }
}

private fun String.normalize(): String = trim().lowercase().replace(Regex("\\s+"), " ")

fun findActiveSegmentIndex(segments: List<RailSegment>, currentHref: String): Int {
    if (segments.isEmpty()) return 0
    val exact = segments.indexOfFirst { it.href == currentHref }
    if (exact >= 0) return exact
    val currentBase = currentHref.substringBefore('#')
    val baseMatch = segments.indexOfFirst { it.href.substringBefore('#') == currentBase }
    return if (baseMatch >= 0) baseMatch else 0
}
