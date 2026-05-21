package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Link

fun List<Link>.toTocEntries(): List<TocEntry> = map { link ->
    TocEntry(
        title = link.title ?: link.href.toString(),
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
