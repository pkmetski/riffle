package com.riffle.core.domain

import java.io.File

sealed class PdfOpenResult {
    data class Success(val pdfFile: File, val lastPosition: String?) : PdfOpenResult()
    data class NetworkError(val cause: Throwable) : PdfOpenResult()
    data object Offline : PdfOpenResult()
}

interface PdfRepository {
    suspend fun openPdf(item: LibraryItem): PdfOpenResult
    suspend fun saveReadingPosition(itemId: String, locatorJson: String)
}
