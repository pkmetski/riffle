package com.riffle.core.domain

import java.io.File

sealed class EpubOpenResult {
    data class Success(val epubFile: File, val lastPosition: String?) : EpubOpenResult()
    data class NetworkError(val cause: Throwable) : EpubOpenResult()
    data object Offline : EpubOpenResult()
    data class BundleTooLarge(val sizeBytes: Long) : EpubOpenResult()
}

sealed class EpubDownloadResult {
    data object Success : EpubDownloadResult()
    data object AlreadyDownloaded : EpubDownloadResult()
    data class NetworkError(val cause: Throwable) : EpubDownloadResult()
}

interface EpubRepository {
    suspend fun openEpub(item: LibraryItem): EpubOpenResult
    suspend fun downloadEpub(item: LibraryItem): EpubDownloadResult
    suspend fun removeDownload(serverId: String, itemId: String)
    fun isDownloaded(serverId: String, itemId: String): Boolean
    fun isCached(serverId: String, itemId: String): Boolean
    suspend fun saveReadingPosition(itemId: String, cfi: String)
}
