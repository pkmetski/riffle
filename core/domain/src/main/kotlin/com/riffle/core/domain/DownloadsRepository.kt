package com.riffle.core.domain

interface DownloadsRepository {
    fun getDownloadedItemIds(): List<String>
    fun getCachedItemIds(): List<String>

    /** Total bytes of the item's local file(s), across the EPUB and PDF stores. */
    fun sizeOf(itemId: String): Long

    /** Removes the permanent download for a single item. Immediate; no Undo. */
    suspend fun removeDownload(itemId: String)

    /** Removes the cached copy for a single item. Immediate; no Undo. */
    suspend fun removeCached(itemId: String)

    suspend fun removeAllDownloads()
    suspend fun clearAllCached()
}
