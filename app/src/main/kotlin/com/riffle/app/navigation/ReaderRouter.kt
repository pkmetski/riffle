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
