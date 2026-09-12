package com.riffle.app.feature.reader

import org.json.JSONObject

/**
 * Normalizes a stored Readium locator JSON for lazy O'Reilly publications.
 *
 * When a book is first read via a fully-assembled EPUB (synthesized offline), Readium stores
 * resource hrefs with the "OEBPS/" container prefix (e.g. "OEBPS/ch09.html"). When the same
 * book is reopened lazily, the reading order links use bare API paths ("ch09.html") without
 * the prefix. Strip the prefix from the stored locator when the bare path matches a known
 * spine href so Readium can locate the resource and navigate to the saved position.
 *
 * [knownHrefs] is the set of href strings from the lazy publication's reading order.
 */
internal fun normalizeLocatorHrefForLazyPub(locatorJson: String, knownHrefs: Set<String>): String {
    val json = runCatching { JSONObject(locatorJson) }.getOrNull() ?: return locatorJson
    val href = json.optString("href").takeIf { it.isNotEmpty() } ?: return locatorJson
    if (href in knownHrefs) return locatorJson
    val stripped = href.removePrefix("OEBPS/")
    if (stripped != href && stripped in knownHrefs) {
        return JSONObject(locatorJson).apply { put("href", stripped) }.toString()
    }
    return locatorJson
}
