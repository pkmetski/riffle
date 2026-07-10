package com.riffle.core.data

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemRef

class DownloadsRepositoryImpl(
    private val epubCacheStore: LocalStore,
    private val epubDownloadsStore: LocalStore,
    private val pdfCacheStore: LocalStore,
    private val pdfDownloadsStore: LocalStore,
    private val cbzCacheStore: LocalStore,
    private val cbzDownloadsStore: LocalStore,
) : DownloadsRepository {

    private val downloadStores = listOf(epubDownloadsStore, pdfDownloadsStore, cbzDownloadsStore)
    private val cacheStores = listOf(epubCacheStore, pdfCacheStore, cbzCacheStore)

    override fun getDownloadedItems(): List<StoredItemRef> =
        downloadStores.flatMap { it.listItems() }.distinct()

    override fun getCachedItems(): List<StoredItemRef> {
        val downloaded = getDownloadedItems().toHashSet()
        return cacheStores.flatMap { it.listItems() }.distinct().filter { it !in downloaded }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        (downloadStores + cacheStores).sumOf { it.get(sourceId, itemId)?.length() ?: 0L }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadStores.forEach { it.delete(sourceId, itemId) }
    }

    override suspend fun removeCached(sourceId: String, itemId: String) {
        cacheStores.forEach { it.delete(sourceId, itemId) }
    }

    override suspend fun removeAllDownloads() {
        downloadStores.forEach { it.clear() }
    }

    override suspend fun clearAllCached() {
        cacheStores.forEach { it.clear() }
    }
}
