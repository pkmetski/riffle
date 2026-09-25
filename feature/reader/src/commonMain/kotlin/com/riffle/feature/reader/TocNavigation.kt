package com.riffle.feature.reader

import com.riffle.core.models.TocEntry

// Table-of-contents navigation over the shared [TocEntry] tree: locating the active entry for a
// href, and flattening the tree into the rows a TOC list renders.
//
// Moved out of `app` for issue #1066. Each platform builds its own [TocEntry] tree from its own
// Readium representation, but everything downstream of that is identical, so it is shared and its
// tests run on both.

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

data class TocRow(val entry: TocEntry, val depth: Int, val orderIndex: Int)

fun flattenToc(entries: List<TocEntry>): List<TocRow> {
    val out = ArrayList<TocRow>()
    fun walk(list: List<TocEntry>, depth: Int) {
        for (e in list) {
            if (e.title.isNotBlank()) {
                out.add(TocRow(e, depth, out.size))
                walk(e.children, depth + 1)
            } else {
                // Preserve legacy behaviour: skip blank-title container, descend at same depth.
                walk(e.children, depth)
            }
        }
    }
    walk(entries, 0)
    return out
}

fun findActiveFlatIndex(
    entries: List<TocEntry>,
    flat: List<TocRow>,
    activeHref: String?,
): Int? {
    if (activeHref == null) return null
    // Match the tree-walking rules of findActiveEntry, then locate the resulting entry in flat.
    // This handles blank-title containers (skipped in flat) by promoting to their first
    // descendant that matches, and keeps the exact-href-first / subtree-fallback behaviour.
    val entry = findActiveEntry(entries, activeHref) ?: return null
    val normalizedEntryHref = entry.href.trimStart('/')
    val exact = flat.indexOfFirst { it.entry.href.trimStart('/') == normalizedEntryHref }
    if (exact >= 0) return exact
    // The matched entry itself was skipped (blank title). Fall through to its first descendant
    // that survived flattening.
    val descendantHrefs = collectHrefs(entry.children).mapTo(HashSet()) { it.trimStart('/') }
    val idx = flat.indexOfFirst { it.entry.href.trimStart('/') in descendantHrefs }
    return if (idx >= 0) idx else null
}

private fun collectHrefs(entries: List<TocEntry>): List<String> {
    val out = ArrayList<String>()
    fun walk(list: List<TocEntry>) {
        for (e in list) {
            out.add(e.href)
            walk(e.children)
        }
    }
    walk(entries)
    return out
}

/**
 * Resolves the best TOC href for highlighting given two signals:
 *  - [locatorHref]: the bare resource path from Readium (never carries a fragment)
 *  - [lastTocNavigatedHref]: the full href — possibly with `#fragment` — of the last TOC entry
 *    the user explicitly tapped
 *
 * When both signals point to the same resource, the last-navigated href is preferred because it
 * carries the subchapter fragment that [locatorHref] loses. When the locator has moved to a
 * different resource (cross-chapter navigation), the last-navigated hint is stale and is dropped.
 */
fun activeTocHref(locatorHref: String?, lastTocNavigatedHref: String?): String? {
    if (lastTocNavigatedHref == null || locatorHref == null) return locatorHref
    return if (locatorHref.substringBefore('#') == lastTocNavigatedHref.substringBefore('#')) {
        lastTocNavigatedHref
    } else {
        locatorHref
    }
}
