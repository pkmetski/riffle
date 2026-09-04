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
 */
fun annotationLocatorJson(chapterHref: String, cfi: String, progression: Double): String {
    val cfiFragment = extractCfiFragment(cfi)
    return buildString {
        append("""{"href":""")
        append('"'); append(chapterHref.escapeJson()); append('"')
        append(""","type":"application/xhtml+xml"""")
        append(""","locations":{"cfi":""")
        append('"'); append(cfiFragment.escapeJson()); append('"')
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

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
