package com.riffle.feature.reader

/**
 * Platform-neutral seam for loading PDF item metadata (title, saved position) and persisting
 * reading positions. The actual PDF opening (navigator creation) stays on the platform side
 * because Android uses Readium and iOS uses PDFKit — both are UI-layer objects.
 */
interface SharedPdfRepository {
    suspend fun loadItem(itemId: String, sourceId: String?): PdfItemResult
    suspend fun saveReadingPosition(sourceId: String, itemId: String, position: String)
}

sealed interface PdfItemResult {
    data class Found(
        val title: String,
        /** 0-based initial page index, decoded from the last saved locator. */
        val initialPage: Int,
    ) : PdfItemResult
    data object BookNotFound : PdfItemResult
    data class NetworkError(val message: String) : PdfItemResult
    data object Offline : PdfItemResult
}
