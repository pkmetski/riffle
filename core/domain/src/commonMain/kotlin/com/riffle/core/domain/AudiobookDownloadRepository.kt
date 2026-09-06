package com.riffle.core.domain

sealed class AudiobookDownloadResult {
    data object Success : AudiobookDownloadResult()
    data class NetworkError(val cause: Throwable) : AudiobookDownloadResult()
}

interface AudiobookDownloadRepository {
    fun isDownloaded(sourceId: String, itemId: String): Boolean
    suspend fun download(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult
    suspend fun remove(sourceId: String, itemId: String): Long
}
