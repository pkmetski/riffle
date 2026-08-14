package com.riffle.core.domain

// The Downloads screen is inherently cross-Source (it lists every file on disk), so it keys by
// (sourceId, itemId) rather than itemId alone (ADR 0031).
interface DownloadsRepository {
    fun getDownloadedArtifacts(): List<StoredItemArtifact>
    fun getCachedArtifacts(): List<StoredItemArtifact>

    fun getDownloadedItems(): List<StoredItemRef> =
        getDownloadedArtifacts().map { it.ref }.distinct()

    fun getCachedItems(): List<StoredItemRef> =
        getCachedArtifacts().map { it.ref }.distinct()

    /** Total bytes of the item's local artifact(s), including directory-backed audiobook data. */
    fun sizeOf(sourceId: String, itemId: String): Long

    /** Removes the permanent download and any hidden same-item cache copy. Immediate; no Undo. */
    suspend fun removeDownload(sourceId: String, itemId: String)

    /** Removes the cached copy for a single item. Immediate; no Undo. */
    suspend fun removeCached(sourceId: String, itemId: String)

    suspend fun removeAllDownloads()
    suspend fun clearAllCached()
}

data class StoredItemArtifact(
    val sourceId: String,
    val itemId: String,
    val mediaType: StoredMediaType,
) {
    val ref: StoredItemRef get() = StoredItemRef(sourceId, itemId)
}

enum class StoredMediaType {
    Epub,
    Pdf,
    Cbz,
    Audiobook,
}
