package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

internal fun buildEpubCfi(readingOrder: List<Link>, href: Url): String =
    buildEpubCfi(readingOrder.map { it.url().toString() }, href.toString())

internal fun buildEpubCfi(readingOrderHrefs: List<String>, hrefString: String): String {
    val spineIndex = readingOrderHrefs.indexOf(hrefString)
    if (spineIndex == -1) return ""
    return "epubcfi(/6/${(spineIndex + 1) * 2}!)"
}
