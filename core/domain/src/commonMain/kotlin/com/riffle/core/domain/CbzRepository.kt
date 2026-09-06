package com.riffle.core.domain

import com.riffle.core.domain.comic.ComicBookmark
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.models.LibraryItem

sealed class CbzOpenResult {
    /**
     * A locally-available comic, already opened as a [ComicPageSource]. The caller owns the source
     * and must [ComicPageSource.close] it when done (archive-backed sources hold a file handle).
     */
    data class Success(
        val imageSource: ComicPageSource,
        val pageCount: Int,
        val lastPosition: String?,
        val bookmarks: List<ComicBookmark> = emptyList(),
    ) : CbzOpenResult()

    /**
     * No local file exists but the catalog supports per-page streaming. [imageSource] fetches pages
     * on demand; a background download can later be awaited via [CbzRepository.awaitCachedSource] to
     * swap to a local archive.
     */
    data class Streaming(
        val imageSource: ComicPageSource,
        val thumbnailSource: ComicPageSource?,
        val pageCount: Int,
        val lastPosition: String?,
    ) : CbzOpenResult()

    data class NetworkError(val cause: Throwable) : CbzOpenResult()
    data object Offline : CbzOpenResult()
}

/** A locally-cached comic opened after a streaming session's background download completes. */
data class CbzLocalSource(
    val imageSource: ComicPageSource,
    val pageCount: Int,
    val bookmarks: List<ComicBookmark> = emptyList(),
)

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
    /** True when the catalog for [sourceId] implements per-page streaming. */
    suspend fun supportsStreaming(sourceId: String): Boolean
    /**
     * Fetch the raw image bytes for [pageIndex] (0-based) directly from the catalog.
     * Only valid when [supportsStreaming] returns true for the item's source.
     */
    suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int? = null): ByteArray
    /**
     * Download the full CBZ to the local cache store and return it opened as a [CbzLocalSource].
     * Returns null on network failure. Idempotent: returns the existing file if already present.
     * Used by the reader to transition from network-streaming to local-archive access.
     */
    suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource?
}

/** JVM extension of [CbzRepository] adding [java.io.File]-returning methods for Android/JVM hosts. */
interface JvmCbzRepository : CbzRepository {
    suspend fun awaitCachedFile(item: LibraryItem): java.io.File?
}
