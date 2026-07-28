package com.riffle.core.domain

/**
 * Resolves an EPUB-internal href, collapsing `.`/`..` segments, optionally against a [base] folder,
 * and preserving any `#fragment`. The single home for the path normalisation the readaloud machinery
 * needs in several places — so a SMIL clip ref written relative to its `.smil` folder
 * (`../text/x.html#id`), the player's resolved ref (`text/x.html#id`), and a spine href all reconcile
 * to the same string. A leading `/` is treated as root-relative (the base is ignored); `..` segments
 * that would escape the root are dropped.
 */
fun resolveEpubHref(href: String, base: String = ""): String {
    val hash = href.indexOf('#')
    val path = if (hash >= 0) href.substring(0, hash) else href
    val fragment = if (hash >= 0) href.substring(hash + 1) else null

    val segments = ArrayDeque<String>()
    if (!path.startsWith('/')) base.split('/').forEach { if (it.isNotEmpty()) segments.addLast(it) }
    for (segment in path.split('/')) when (segment) {
        "", "." -> {}
        ".." -> if (segments.isNotEmpty()) segments.removeLast()
        else -> segments.addLast(segment)
    }
    val resolved = segments.joinToString("/")
    return if (fragment != null) "$resolved#$fragment" else resolved
}

/**
 * Extracts the EPUB-internal resource path from any URL variant:
 *  - file:///path/to/book.epub!/OEBPS/chapter1.xhtml  → OEBPS/chapter1.xhtml
 *  - http://localhost:PORT/OEBPS/chapter1.xhtml        → OEBPS/chapter1.xhtml
 *  - OEBPS/chapter1.xhtml (already relative)          → OEBPS/chapter1.xhtml
 */
fun normalizeEpubHref(raw: String): String {
    val bang = raw.lastIndexOf('!')
    return if (bang >= 0) {
        raw.substring(bang + 1).trimStart('/')
    } else {
        try {
            java.net.URI(raw).path?.trimStart('/') ?: raw
        } catch (_: Exception) {
            raw
        }
    }
}

/**
 * Extracts the 0-based spine index from an EPUB CFI string.
 * EPUB CFI format: epubcfi(/6/{step}!/...) where step = (spineIndex + 1) * 2.
 * Returns null if the CFI is null, empty, doesn't match the pattern, or the step is invalid.
 */
fun epubCfiToSpineIndex(cfi: String): Int? {
    val match = Regex("""epubcfi\(/6/(\d+)""").find(cfi) ?: return null
    val step = match.groupValues[1].toIntOrNull() ?: return null
    if (step < 2 || step % 2 != 0) return null
    return step / 2 - 1
}
