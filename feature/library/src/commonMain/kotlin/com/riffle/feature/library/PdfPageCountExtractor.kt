package com.riffle.feature.library

import com.riffle.core.models.LibraryItem

interface PdfPageCountExtractor {
    suspend fun extract(item: LibraryItem): Int?
}
