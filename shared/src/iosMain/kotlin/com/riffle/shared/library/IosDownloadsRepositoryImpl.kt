package com.riffle.shared.library

import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize

@OptIn(ExperimentalForeignApi::class)
internal class IosDownloadsRepositoryImpl(
    private val fileStore: FileStore,
) : DownloadsRepository {

    override fun getDownloadedArtifacts(): List<StoredItemArtifact> =
        scanNamespace(NS_EPUB_DOWNLOADS, ".epub", StoredMediaType.Epub)

    override fun getCachedArtifacts(): List<StoredItemArtifact> =
        scanNamespace(NS_EPUB_CACHE, ".epub", StoredMediaType.Epub)

    override fun sizeOf(sourceId: String, itemId: String): Long {
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub")
        val cachePath = fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub")
        return fileSizeAt(downloadPath) + fileSizeAt(cachePath)
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(
            fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub"), null
        )
    }

    override suspend fun removeCached(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(
            fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub"), null
        )
    }

    override suspend fun removeAllDownloads() {
        getDownloadedArtifacts().forEach { removeDownload(it.sourceId, it.itemId) }
    }

    override suspend fun clearAllCached() {
        getCachedArtifacts().forEach { removeCached(it.sourceId, it.itemId) }
    }

    private fun scanNamespace(namespace: String, ext: String, mediaType: StoredMediaType): List<StoredItemArtifact> {
        val root = fileStore.resolve(namespace, "")

        @Suppress("UNCHECKED_CAST")
        val sourceDirs = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null) as? List<String>
            ?: return emptyList()

        return sourceDirs.flatMap { sourceId ->
            val sourceDir = "$root/$sourceId"

            @Suppress("UNCHECKED_CAST")
            val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(sourceDir, null) as? List<String>
                ?: return@flatMap emptyList()
            files.filter { it.endsWith(ext) }
                .map { filename ->
                    val itemId = filename.removeSuffix(ext)
                    StoredItemArtifact(sourceId = sourceId, itemId = itemId, mediaType = mediaType)
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fileSizeAt(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) as? Map<Any?, Any?>
            ?: return 0L
        return (attrs[NSFileSize] as? Long) ?: 0L
    }
}
