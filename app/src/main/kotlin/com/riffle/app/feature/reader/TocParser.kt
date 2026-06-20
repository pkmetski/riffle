package com.riffle.app.feature.reader

import com.riffle.core.domain.TocEntry
import org.readium.r2.shared.publication.Link

fun List<Link>.toTocEntries(): List<TocEntry> = map { link ->
    TocEntry(
        title = link.title.orEmpty(),
        href = link.href.toString(),
        children = link.children.toTocEntries(),
    )
}

fun findActiveEntry(entries: List<TocEntry>, currentHref: String): TocEntry? {
    for (entry in entries) {
        if (entry.href == currentHref) return entry
        val child = findActiveEntry(entry.children, currentHref)
        if (child != null) return child
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
