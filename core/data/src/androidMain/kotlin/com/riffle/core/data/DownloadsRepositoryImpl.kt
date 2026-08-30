package com.riffle.core.data

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import java.io.File

class DownloadsRepositoryImpl(
    private val epubCacheStore: LocalStore,
    private val epubDownloadsStore: LocalStore,
    private val pdfCacheStore: LocalStore,
    private val pdfDownloadsStore: LocalStore,
    private val cbzCacheStore: LocalStore,
    private val cbzDownloadsStore: LocalStore,
    private val audiobookCacheDir: File,
    private val audiobookDownloadsDir: File,
    private val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
) : DownloadsRepository {

    private val downloadStores = listOf(
        TypedStore(epubDownloadsStore, StoredMediaType.Epub),
        TypedStore(pdfDownloadsStore, StoredMediaType.Pdf),
        TypedStore(cbzDownloadsStore, StoredMediaType.Cbz),
    )
    private val cacheStores = listOf(
        TypedStore(epubCacheStore, StoredMediaType.Epub),
        TypedStore(pdfCacheStore, StoredMediaType.Pdf),
        TypedStore(cbzCacheStore, StoredMediaType.Cbz),
    )
    private val manifestName = "manifest.json"

    override fun getDownloadedArtifacts(): List<StoredItemArtifact> =
        (
            downloadStores.flatMap { it.listArtifacts() } +
                listDirectoryBackedArtifacts(audiobookDownloadsDir, StoredMediaType.Audiobook)
            ).distinct()

    override fun getCachedArtifacts(): List<StoredItemArtifact> {
        val downloaded = getDownloadedItems().toHashSet()
        return (
            cacheStores.flatMap { it.listArtifacts() } +
                listDirectoryBackedArtifacts(audiobookCacheDir, StoredMediaType.Audiobook)
            )
            .distinct()
            .filter { it.ref !in downloaded }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        (downloadStores + cacheStores).sumOf { it.store.get(sourceId, itemId)?.length() ?: 0L } +
            directorySize(itemDir(audiobookDownloadsDir, sourceId, itemId)) +
            directorySize(itemDir(audiobookCacheDir, sourceId, itemId))

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadStores.forEach { it.store.delete(sourceId, itemId) }
        cacheStores.forEach { it.store.delete(sourceId, itemId) }
        itemDir(audiobookDownloadsDir, sourceId, itemId).deleteRecursively()
        itemDir(audiobookCacheDir, sourceId, itemId).deleteRecursively()
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override suspend fun removeCached(sourceId: String, itemId: String) {
        cacheStores.forEach { it.store.delete(sourceId, itemId) }
        itemDir(audiobookCacheDir, sourceId, itemId).deleteRecursively()
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override suspend fun removeAllDownloads() {
        downloadStores.forEach { it.store.clear() }
        audiobookDownloadsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun clearAllCached() {
        cacheStores.forEach { it.store.clear() }
        audiobookCacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun itemDir(root: File, sourceId: String, itemId: String): File =
        root.resolve(sourceId).resolve(itemId)

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun listDirectoryBackedArtifacts(root: File, mediaType: StoredMediaType): List<StoredItemArtifact> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { sourceDir ->
                val prefix = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name == manifestName }
                    .map { manifest ->
                        val itemDir = requireNotNull(manifest.parentFile) { "manifest without parent: $manifest" }
                        StoredItemArtifact(
                            sourceId = sourceDir.name,
                            itemId = itemDir.absolutePath.removePrefix(prefix),
                            mediaType = mediaType,
                        )
                    }
                    .toList()
            }
            ?: emptyList()

    private data class TypedStore(val store: LocalStore, val mediaType: StoredMediaType) {
        fun listArtifacts(): List<StoredItemArtifact> =
            store.listItems().map {
                StoredItemArtifact(
                    sourceId = it.sourceId,
                    itemId = it.itemId,
                    mediaType = mediaType,
                )
            }
    }
}
