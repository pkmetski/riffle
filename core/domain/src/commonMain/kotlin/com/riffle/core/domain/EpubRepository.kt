package com.riffle.core.domain

import com.riffle.core.models.LibraryItem

sealed class EpubDownloadResult {
    data object Success : EpubDownloadResult()
    data object AlreadyDownloaded : EpubDownloadResult()
    data class NetworkError(val cause: Throwable) : EpubDownloadResult()
}

interface EpubRepository {
    suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): EpubDownloadResult
    suspend fun removeDownload(sourceId: String, itemId: String)
    fun isDownloaded(sourceId: String, itemId: String): Boolean
    fun isCached(sourceId: String, itemId: String): Boolean
    suspend fun saveReadingPosition(itemId: String, cfi: String)
    suspend fun loadLastPosition(sourceId: String, itemId: String): String? = null
    suspend fun loadLastPositionHref(sourceId: String, itemId: String): String? = null
}
