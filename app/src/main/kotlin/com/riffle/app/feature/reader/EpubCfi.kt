package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

/**
 * Builds a chapter-level epub.js CFI for the given locator href within a publication's reading order.
 *
 * Readium's Link.url() returns null for relative hrefs (no base URL to resolve against), so we
 * compare Link.href.toString() directly against locator.href.toString(). If a direct match fails,
 * we fall back to normalized path comparison to handle file:// vs http://localhost URL mismatches
 * that can occur depending on how the publication asset was opened.
 */
internal fun buildEpubCfi(readingOrder: List<Link>, href: Url): String {
    val hrefStr = href.toString()
    val hrefNorm = normalizeEpubHref(hrefStr)
    val spineIndex = readingOrder.indexOfFirst { link ->
        val linkStr = link.href.toString()
        linkStr == hrefStr || normalizeEpubHref(linkStr) == hrefNorm
    }
    if (spineIndex == -1) return ""
    return "epubcfi(/6/${(spineIndex + 1) * 2}!/4/2)"
}

internal fun buildEpubCfi(readingOrderHrefs: List<String>, hrefString: String): String {
    val spineIndex = readingOrderHrefs.indexOf(hrefString)
    if (spineIndex == -1) return ""
    return "epubcfi(/6/${(spineIndex + 1) * 2}!/4/2)"
}

/**
 * Extracts the 0-based spine index from an EPUB CFI string.
 * EPUB CFI format: epubcfi(/6/{step}!/...) where step = (spineIndex + 1) * 2
 * Returns null if the CFI is null, empty, doesn't match the pattern, or the step is invalid.
 */
internal fun epubCfiToSpineIndex(cfi: String): Int? {
    val match = Regex("""epubcfi\(/6/(\d+)""").find(cfi) ?: return null
    val step = match.groupValues[1].toIntOrNull() ?: return null
    if (step < 2 || step % 2 != 0) return null
    return step / 2 - 1
}

/**
 * Extracts the EPUB-internal resource path from any URL variant:
 *  - file:///path/to/book.epub!/OEBPS/chapter1.xhtml  → OEBPS/chapter1.xhtml
 *  - http://localhost:PORT/OEBPS/chapter1.xhtml        → OEBPS/chapter1.xhtml
 *  - OEBPS/chapter1.xhtml (already relative)          → OEBPS/chapter1.xhtml
 */
internal fun normalizeEpubHref(raw: String): String {
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
