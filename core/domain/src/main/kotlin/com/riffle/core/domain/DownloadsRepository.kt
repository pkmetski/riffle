package com.riffle.core.domain

// The Downloads screen is inherently cross-Server (it lists every file on disk), so it keys by
// (sourceId, itemId) rather than itemId alone (ADR 0025).
interface DownloadsRepository {
    fun getDownloadedItems(): List<StoredItemRef>
    fun getCachedItems(): List<StoredItemRef>

    /** Total bytes of the item's local file(s), across the EPUB and PDF stores. */
    fun sizeOf(sourceId: String, itemId: String): Long

    /** Removes the permanent download for a single item. Immediate; no Undo. */
    suspend fun removeDownload(sourceId: String, itemId: String)

    /** Removes the cached copy for a single item. Immediate; no Undo. */
    suspend fun removeCached(sourceId: String, itemId: String)

    suspend fun removeAllDownloads()
    suspend fun clearAllCached()
}
