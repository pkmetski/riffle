package com.riffle.app.navigation

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import java.net.URLEncoder

fun readerRouteFor(item: LibraryItem): String? {
    val encodedId = URLEncoder.encode(item.id, "UTF-8")
    return when (item.ebookFormat) {
        EbookFormat.Epub -> "epub_reader/$encodedId"
        EbookFormat.Pdf -> "pdf_reader/$encodedId"
        EbookFormat.Unsupported -> null
    }
}

/**
 * The Annotations-list "open a book" route (ADR 0041, Important #1 fix). Threads [serverId]
 * through explicitly as a `?serverId=` nav arg rather than letting the reader re-resolve "the
 * active server" at open time — a Server Switcher change racing this navigation would otherwise
 * open the elided reader against whatever server happens to be active when it opens, not the
 * server the tapped book's highlights actually belong to, silently showing zero highlights.
 * `internal` (top-level, not inlined into MainScreen's composable lambda) so this exact string
 * shape is unit-testable without a NavHost.
 */
internal fun annotationsBookClickRoute(serverId: String, itemId: String): String {
    val encodedItemId = URLEncoder.encode(itemId, "UTF-8")
    val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
    return "epub_reader/$encodedItemId?source=highlights&serverId=$encodedServerId"
}
