package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry
import org.readium.r2.shared.publication.Link

fun List<Link>.toTocEntries(): List<TocEntry> = map { link ->
    TocEntry(
        title = link.title.orEmpty(),
        href = link.href.toString(),
        children = link.children.toTocEntries(),
    )
}

fun findActiveEntry(entries: List<TocEntry>, currentHref: String): TocEntry? {
    findExactEntry(entries, currentHref)?.let { return it }
    // Fallback: Readium's Locator.href never carries a fragment (fragments live in
    // locations.fragments), but nav documents commonly anchor entries like
    // `xhtml/chapter1.xhtml#ch1`. Without this fallback the exact-string test above
    // silently fails for every fragment-anchored chapter, so nothing lights up in books
    // like "Extreme Ownership" whose nav puts a `#chN` on every entry.
    val currentPath = currentHref.trimStart('/').substringBefore('#')
    if (currentPath.isEmpty()) return null
    return findByPath(entries, currentPath)
}

private fun findExactEntry(entries: List<TocEntry>, currentHref: String): TocEntry? {
    val target = currentHref.trimStart('/')
    for (entry in entries) {
        if (entry.href.trimStart('/') == target) return entry
        findExactEntry(entry.children, currentHref)?.let { return it }
    }
    return null
}

private fun findByPath(entries: List<TocEntry>, currentPath: String): TocEntry? {
    for (entry in entries) {
        if (entry.href.trimStart('/').substringBefore('#') == currentPath) return entry
        findByPath(entry.children, currentPath)?.let { return it }
    }
    return null
}

/**
 * Index into [entries] (the top-level list rendered by the TOC's `LazyColumn`) of the entry whose
 * subtree contains [currentHref], or null if nothing matches. A nested child resolves to the index
 * of its top-level ancestor, since children are rendered within their ancestor's list item.
 */
fun findActiveTopLevelIndex(entries: List<TocEntry>, currentHref: String?): Int? {
    if (currentHref == null) return null
    val index = entries.indexOfFirst { findActiveEntry(listOf(it), currentHref) != null }
    return index.takeIf { it >= 0 }
}
