package com.riffle.core.data

/**
 * Builds a Readium-compatible Locator JSON string from stored annotation fields.
 *
 * Readium Locator JSON shape:
 * ```json
 * {
 *   "href": "EPUB/chapter.xhtml",
 *   "type": "application/xhtml+xml",
 *   "locations": { "cfi": "/4/2/16)", "progression": 0.5 }
 * }
 * ```
 *
 * The stored CFI is the full `epubcfi(…)` string. Readium Swift's locations.cfi expects only
 * the intra-document fragment — the part after the `!`. If no `!` is present (malformed CFI)
 * the field is left as an empty string so the locator is still valid (position-based fallback).
 *
 * **Not for decorations.** Readium's decoration renderer never reads `locations.cfi`, so a
 * locator built here places nothing on the page — use [annotationDecorationLocatorJson], which
 * carries the text-quote anchor Readium can actually resolve.
 */
fun annotationLocatorJson(chapterHref: String, cfi: String, progression: Double): String {
    val cfiFragment = extractCfiFragment(cfi)
    return buildString {
        append("""{"href":""")
        append('"')
        append(chapterHref.escapeJson())
        append('"')
        append(""","type":"application/xhtml+xml"""")
        append(""","locations":{"cfi":""")
        append('"')
        append(cfiFragment.escapeJson())
        append('"')
        append(""","progression":""")
        append(progression)
        append("}}")
    }
}

/**
 * The locator a *decoration* is anchored with, as opposed to the one a *navigation* uses.
 *
 * Readium's decoration renderer resolves a locator to a DOM range in `utils.js`
 * (`rangeFromLocator`) and it reads exactly three things, in this order:
 *
 *  1. `text.highlight` (+ `text.before` / `text.after`) — resolved with a `TextQuoteAnchor`;
 *  2. `locations.cssSelector`;
 *  3. `locations.fragments` — the first id that resolves to an element.
 *
 * **`locations.cfi` is not consulted at all.** [annotationLocatorJson], which emits only `cfi`
 * and `progression`, therefore produces a locator every decoration silently fails to place:
 * `rangeFromLocator` returns null, `DecorationGroup.add` logs "Can't locate DOM range" and drops
 * it. That is why no user annotation has ever appeared on an iOS page — the colour fix in #1071
 * corrected a tint that was never painted.
 *
 * So this builds the anchor Readium can actually use, from the fields Riffle already stores:
 * `textSnippet` / `textBefore` / `textAfter` are the text-quote anchor (the same text-anchored
 * model readaloud uses — see `ReadaloudHighlight`), [Annotation.fragmentAnchor] becomes
 * `locations.fragments` so a bookmark lands on its captured paragraph element (PR #671), and
 * `cfi` + `progression` are still carried for the navigation path and for ordering.
 *
 * A bookmark has no meaningful selected text, so its snippet is deliberately NOT emitted as a
 * text anchor when a [Annotation.fragmentAnchor] is present: the element id is the precise
 * anchor, and a quote search for the page's first sentence would drift to the first matching
 * occurrence in the chapter instead.
 */
fun annotationDecorationLocatorJson(
    chapterHref: String,
    cfi: String,
    progression: Double,
    textSnippet: String,
    textBefore: String,
    textAfter: String,
    fragmentAnchor: String?,
): String {
    val cfiFragment = extractCfiFragment(cfi)
    val useTextAnchor = fragmentAnchor.isNullOrEmpty() && textSnippet.isNotEmpty()
    return buildString {
        append("""{"href":""")
        append('"')
        append(chapterHref.escapeJson())
        append('"')
        append(""","type":"application/xhtml+xml"""")
        if (useTextAnchor) {
            append(""","text":{"highlight":""")
            append('"')
            append(textSnippet.escapeJson())
            append('"')
            if (textBefore.isNotEmpty()) {
                append(""","before":""")
                append('"')
                append(textBefore.escapeJson())
                append('"')
            }
            if (textAfter.isNotEmpty()) {
                append(""","after":""")
                append('"')
                append(textAfter.escapeJson())
                append('"')
            }
            append('}')
        }
        append(""","locations":{"cfi":""")
        append('"')
        append(cfiFragment.escapeJson())
        append('"')
        if (!fragmentAnchor.isNullOrEmpty()) {
            append(""","fragments":[""")
            append('"')
            append(fragmentAnchor.escapeJson())
            append('"')
            append(']')
        }
        append(""","progression":""")
        append(progression)
        append("}}")
    }
}

/**
 * Extracts the intra-document CFI fragment (everything after `!` inside `epubcfi(…)`).
 * Returns empty string if the CFI has no `!`.
 */
internal fun extractCfiFragment(fullCfi: String): String {
    val bangIndex = fullCfi.indexOf('!')
    if (bangIndex < 0) return ""
    return fullCfi.substring(bangIndex + 1)
}

/**
 * Escapes a value for embedding between the quotes of a JSON string literal.
 *
 * Hrefs and CFIs are single-line, so the original two replacements were enough for
 * [annotationLocatorJson]. A highlight's `textSnippet` is not: a selection that spans a
 * paragraph break carries a literal newline, and a raw control character inside a JSON string
 * is a parse error — `JSONValue(jsonString:)` on the Swift side rejects the whole locator and
 * the decoration disappears, which is indistinguishable from "the range could not be found".
 */
private fun String.escapeJson(): String = buildString(length) {
    for (ch in this@escapeJson) {
        when {
            ch == '\\' -> append("\\\\")
            ch == '"' -> append("\\\"")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            }
            else -> append(ch)
        }
    }
}
