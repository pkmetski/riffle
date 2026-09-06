package com.riffle.core.domain

import java.io.File
import com.riffle.core.models.LibraryItem

sealed class PdfOpenResult {
    data class Success(
        val pdfFile: File,
        val lastPosition: String?,
        val temporary: Boolean = false,
    ) : PdfOpenResult()
    data class NetworkError(val cause: Throwable) : PdfOpenResult()
    data object Offline : PdfOpenResult()
}

interface JvmPdfRepository : PdfRepository {
    suspend fun openPdf(item: LibraryItem): PdfOpenResult
    suspend fun openPdfForMetadata(item: LibraryItem): PdfOpenResult = openPdf(item)
}
