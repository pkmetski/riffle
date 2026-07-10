package com.riffle.core.domain

import java.io.File

sealed class CbzOpenResult {
    data class Success(val cbzFile: File, val lastPosition: String?) : CbzOpenResult()
    data class NetworkError(val cause: Throwable) : CbzOpenResult()
    data object Offline : CbzOpenResult()
}

sealed class CbzDownloadResult {
    data object Success : CbzDownloadResult()
    data object AlreadyDownloaded : CbzDownloadResult()
    data class NetworkError(val cause: Throwable) : CbzDownloadResult()
}

interface CbzRepository {
    suspend fun openCbz(item: LibraryItem): CbzOpenResult
    suspend fun downloadCbz(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): CbzDownloadResult
    suspend fun removeDownload(sourceId: String, itemId: String)
    fun isDownloaded(sourceId: String, itemId: String): Boolean
    fun isCached(sourceId: String, itemId: String): Boolean
    suspend fun saveReadingPosition(itemId: String, locatorJson: String)
}
