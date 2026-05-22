package com.riffle.core.domain

import java.io.File

sealed class PdfOpenResult {
    data class Success(val pdfFile: File, val lastPosition: String?) : PdfOpenResult()
    data class NetworkError(val cause: Throwable) : PdfOpenResult()
    data object Offline : PdfOpenResult()
}

sealed class PdfDownloadResult {
    data object Success : PdfDownloadResult()
    data object AlreadyDownloaded : PdfDownloadResult()
    data class NetworkError(val cause: Throwable) : PdfDownloadResult()
}

interface PdfRepository {
    suspend fun openPdf(item: LibraryItem): PdfOpenResult
    suspend fun downloadPdf(item: LibraryItem): PdfDownloadResult
    suspend fun removeDownload(itemId: String)
    fun isDownloaded(itemId: String): Boolean
    fun isCached(itemId: String): Boolean
    suspend fun saveReadingPosition(itemId: String, locatorJson: String)
}
