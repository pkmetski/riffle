package com.riffle.core.domain

import com.riffle.core.models.LibraryItem

sealed class PdfDownloadResult {
    data object Success : PdfDownloadResult()
    data object AlreadyDownloaded : PdfDownloadResult()
    data class NetworkError(val cause: Throwable) : PdfDownloadResult()
}

interface PdfRepository {
    suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): PdfDownloadResult
    suspend fun removeDownload(sourceId: String, itemId: String)
    fun isDownloaded(sourceId: String, itemId: String): Boolean
    fun isCached(sourceId: String, itemId: String): Boolean
    suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String)
}
