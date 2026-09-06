package com.riffle.core.domain

sealed interface AudioDownloadResult {
    data object Success : AudioDownloadResult
    data object NoBundle : AudioDownloadResult
    data class NetworkError(val cause: Throwable) : AudioDownloadResult
}

interface ReadaloudAudioRepository {
    fun isAudioAvailable(sourceId: String, itemId: String): Boolean
    suspend fun probeSizeBytes(sourceId: String, itemId: String): Long?
    suspend fun downloadAudio(
        sourceId: String,
        bookId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudioDownloadResult
    suspend fun removeAudio(sourceId: String, itemId: String): Long
}
