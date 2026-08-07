package com.riffle.core.domain

import java.io.File
import com.riffle.core.models.LibraryItem

sealed class CbzOpenResult {
    data class Success(val cbzFile: File, val lastPosition: String?) : CbzOpenResult()
    /**
     * No local file exists but the catalog supports per-page streaming. The reader may open
     * immediately using [CbzRepository.fetchStreamingPageImage]; a background download will
     * populate the local cache and the reader can swap to local access when ready.
     */
    data class Streaming(val pageCount: Int, val lastPosition: String?) : CbzOpenResult()
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
    /** True when the catalog for [sourceId] implements [CbzPageStreamCapability]. */
    suspend fun supportsStreaming(sourceId: String): Boolean
    /**
     * Fetch the raw image bytes for [pageIndex] (0-based) directly from the catalog.
     * Only valid when [supportsStreaming] returns true for the item's source.
     */
    suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int): ByteArray
    /**
     * Download the full CBZ to the local cache store and return the [File].
     * Returns null on network failure. Idempotent: returns the existing file if already present.
     * This is used by the reader to transition from network-streaming to local-archive access.
     */
    suspend fun awaitCachedFile(item: LibraryItem): File?
}
