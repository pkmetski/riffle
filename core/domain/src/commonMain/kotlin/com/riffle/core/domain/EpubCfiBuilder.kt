package com.riffle.core.domain

/**
 * Builds a chapter-level epub.js CFI pointing at the start of the spine item [hrefString], or an
 * empty string when that href is not in [readingOrderHrefs].
 *
 * The `/6/<spine step>!/4/2` shape is the same one the CFI translators parse back out (ADR 0013),
 * so it lives here rather than in a platform module: Android's Readium-typed overload and iOS's
 * translator both derive from this one definition.
 */
fun buildEpubCfi(readingOrderHrefs: List<String>, hrefString: String): String {
    val spineIndex = readingOrderHrefs.indexOf(hrefString)
    if (spineIndex == -1) return ""
    return epubCfiForSpineIndex(spineIndex)
}

/** The chapter-level CFI for a zero-based spine index. */
fun epubCfiForSpineIndex(spineIndex: Int): String = "epubcfi(/6/${(spineIndex + 1) * 2}!/4/2)"
