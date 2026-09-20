package com.riffle.core.domain

/**
 * One typed on-disk artifact store as the Downloads policy sees it — e.g. "cached PDFs" or
 * "downloaded audiobooks". Platforms supply their own file-system-backed implementations
 * (Android over `LocalStore`/`java.io.File`, iOS over `NSFileManager`); everything about
 * *which* stores are consulted and in what order lives in [StoredArtifactDownloadsRepository]
 * so both platforms share one policy instead of re-deriving it.
 */
interface StoredArtifactStore {
    /** The media type every artifact this store lists carries. */
    val mediaType: StoredMediaType

    /** Every artifact currently on disk in this store. */
    fun list(): List<StoredItemArtifact>

    /** Bytes this store holds for the item, or 0 when it holds nothing. */
    fun sizeOf(sourceId: String, itemId: String): Long

    /** Removes this store's copy of the item. A no-op when there is none. */
    fun delete(sourceId: String, itemId: String)

    /** Removes everything this store holds. */
    fun clear()
}

/**
 * The platform-neutral Downloads policy shared by Android and iOS.
 *
 * Three behaviours here are easy to get wrong one store at a time and are therefore pinned in
 * `commonTest` rather than per platform:
 *
 *  - **Cached is always net of downloaded.** An item that is permanently downloaded must not
 *    also appear under "Cached", or the Downloads screen double-lists it and double-counts its
 *    size.
 *  - **Removing a download removes the hidden cache copy too** (see [DownloadsRepository]).
 *    Deleting only the downloads copy leaves the item offline-available and makes it reappear
 *    under "Cached" the moment the screen reloads.
 *  - **Every single-item removal notifies [LocalAvailabilityEvents]**, so offline badges
 *    elsewhere in the app refresh instead of going stale until the next cold start.
 */
class StoredArtifactDownloadsRepository(
    private val downloadStores: List<StoredArtifactStore>,
    private val cacheStores: List<StoredArtifactStore>,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
) : DownloadsRepository {

    override fun getDownloadedArtifacts(): List<StoredItemArtifact> =
        downloadStores.flatMap { it.list() }.distinct()

    override fun getCachedArtifacts(): List<StoredItemArtifact> {
        val downloaded = getDownloadedItems().toHashSet()
        return cacheStores.flatMap { it.list() }
            .distinct()
            .filter { it.ref !in downloaded }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        (downloadStores + cacheStores).sumOf { it.sizeOf(sourceId, itemId) }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadStores.forEach { it.delete(sourceId, itemId) }
        cacheStores.forEach { it.delete(sourceId, itemId) }
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override suspend fun removeCached(sourceId: String, itemId: String) {
        cacheStores.forEach { it.delete(sourceId, itemId) }
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override suspend fun removeAllDownloads() {
        downloadStores.forEach { it.clear() }
    }

    override suspend fun clearAllCached() {
        cacheStores.forEach { it.clear() }
    }
}
