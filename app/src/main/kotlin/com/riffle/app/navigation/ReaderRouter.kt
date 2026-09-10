package com.riffle.app.navigation

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import java.net.URLEncoder

fun readerRouteFor(item: LibraryItem): String? {
    val encodedId = URLEncoder.encode(item.id, "UTF-8")
    val sourceParam = if (item.sourceId.isNotBlank()) {
        "?sourceId=${URLEncoder.encode(item.sourceId, "UTF-8")}"
    } else {
        ""
    }
    return when (item.ebookFormat) {
        EbookFormat.Epub -> "epub_reader/$encodedId$sourceParam"
        EbookFormat.Pdf -> "pdf_reader/$encodedId$sourceParam"
        EbookFormat.Cbz -> "cbz_reader/$encodedId$sourceParam"
        EbookFormat.Unsupported -> null
    }
}

/**
 * The Annotations-list "open a book" route (ADR 0048, Important #1 fix). Threads [sourceId]
 * through explicitly as a `?sourceId=` nav arg rather than letting the reader re-resolve "the
 * active server" at open time — a Server Switcher change racing this navigation would otherwise
 * open the elided reader against whatever server happens to be active when it opens, not the
 * server the tapped book's highlights actually belong to, silently showing zero highlights.
 * `internal` (top-level, not inlined into MainScreen's composable lambda) so this exact string
 * shape is unit-testable without a NavHost.
 */
internal fun annotationsBookClickRoute(sourceId: String, itemId: String): String {
    val encodedItemId = URLEncoder.encode(itemId, "UTF-8")
    val encodedServerId = URLEncoder.encode(sourceId, "UTF-8")
    return "epub_reader/$encodedItemId?source=highlights&sourceId=$encodedServerId"
}
