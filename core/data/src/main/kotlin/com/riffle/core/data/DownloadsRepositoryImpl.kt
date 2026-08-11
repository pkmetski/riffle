package com.riffle.core.data

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemRef
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
) : DownloadsRepository {

    private val downloadStores = listOf(epubDownloadsStore, pdfDownloadsStore, cbzDownloadsStore)
    private val cacheStores = listOf(epubCacheStore, pdfCacheStore, cbzCacheStore)
    private val manifestName = "manifest.json"

    override fun getDownloadedItems(): List<StoredItemRef> =
        (downloadStores.flatMap { it.listItems() } + listDirectoryBackedItems(audiobookDownloadsDir)).distinct()

    override fun getCachedItems(): List<StoredItemRef> {
        val downloaded = getDownloadedItems().toHashSet()
        return (cacheStores.flatMap { it.listItems() } + listDirectoryBackedItems(audiobookCacheDir))
            .distinct()
            .filter { it !in downloaded }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        (downloadStores + cacheStores).sumOf { it.get(sourceId, itemId)?.length() ?: 0L } +
            directorySize(itemDir(audiobookDownloadsDir, sourceId, itemId)) +
            directorySize(itemDir(audiobookCacheDir, sourceId, itemId))

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadStores.forEach { it.delete(sourceId, itemId) }
        itemDir(audiobookDownloadsDir, sourceId, itemId).deleteRecursively()
    }

    override suspend fun removeCached(sourceId: String, itemId: String) {
        cacheStores.forEach { it.delete(sourceId, itemId) }
        itemDir(audiobookCacheDir, sourceId, itemId).deleteRecursively()
    }

    override suspend fun removeAllDownloads() {
        downloadStores.forEach { it.clear() }
        audiobookDownloadsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun clearAllCached() {
        cacheStores.forEach { it.clear() }
        audiobookCacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun itemDir(root: File, sourceId: String, itemId: String): File =
        root.resolve(sourceId).resolve(itemId)

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun listDirectoryBackedItems(root: File): List<StoredItemRef> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { sourceDir ->
                val prefix = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name == manifestName }
                    .map { manifest ->
                        val itemDir = requireNotNull(manifest.parentFile) { "manifest without parent: $manifest" }
                        StoredItemRef(
                            sourceId = sourceDir.name,
                            itemId = itemDir.absolutePath.removePrefix(prefix),
                        )
                    }
                    .toList()
            }
            ?: emptyList()
}
