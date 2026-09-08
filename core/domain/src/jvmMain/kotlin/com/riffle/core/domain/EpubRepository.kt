package com.riffle.core.domain

import java.io.File
import com.riffle.core.models.LibraryItem

sealed class EpubOpenResult {
    data class Success(
        val epubFile: File,
        val lastPosition: String?,
        val temporary: Boolean = false,
    ) : EpubOpenResult()
    data class NetworkError(val cause: Throwable) : EpubOpenResult()
    data object Offline : EpubOpenResult()
}

interface JvmEpubRepository : EpubRepository {
    suspend fun openEpub(item: LibraryItem): EpubOpenResult
    suspend fun openEpubForMetadata(item: LibraryItem): EpubOpenResult = openEpub(item)
    /**
     * Write a pre-assembled EPUB (e.g. assembled from a lazy chapter cache) to the ebook cache
     * store and fire [LocalAvailabilityEvents] so the offline-availability indicator updates.
     * No-op if the item is already cached or downloaded — checking avoids double-writes when the
     * book is opened again before the prefetch scope has been cancelled.
     */
    suspend fun cacheEpub(sourceId: String, itemId: String, bytes: ByteArray)
}
