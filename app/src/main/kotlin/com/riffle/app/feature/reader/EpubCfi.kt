package com.riffle.app.feature.reader

import com.riffle.core.domain.normalizeEpubHref
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
