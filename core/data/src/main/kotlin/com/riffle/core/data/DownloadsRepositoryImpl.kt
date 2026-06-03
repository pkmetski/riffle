package com.riffle.core.data

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredItemRef

class DownloadsRepositoryImpl(
    private val epubCacheStore: LocalStore,
    private val epubDownloadsStore: LocalStore,
    private val pdfCacheStore: LocalStore,
    private val pdfDownloadsStore: LocalStore,
) : DownloadsRepository {

    override fun getDownloadedItems(): List<StoredItemRef> =
        (epubDownloadsStore.listItems() + pdfDownloadsStore.listItems()).distinct()

    override fun getCachedItems(): List<StoredItemRef> {
        val downloaded = getDownloadedItems().toHashSet()
        return (epubCacheStore.listItems() + pdfCacheStore.listItems())
            .distinct()
            .filter { it !in downloaded }
    }

    override fun sizeOf(serverId: String, itemId: String): Long =
        listOf(epubDownloadsStore, pdfDownloadsStore, epubCacheStore, pdfCacheStore)
            .sumOf { it.get(serverId, itemId)?.length() ?: 0L }

    override suspend fun removeDownload(serverId: String, itemId: String) {
        epubDownloadsStore.delete(serverId, itemId)
        pdfDownloadsStore.delete(serverId, itemId)
    }

    override suspend fun removeCached(serverId: String, itemId: String) {
        epubCacheStore.delete(serverId, itemId)
        pdfCacheStore.delete(serverId, itemId)
    }

    override suspend fun removeAllDownloads() {
        epubDownloadsStore.clear()
        pdfDownloadsStore.clear()
    }

    override suspend fun clearAllCached() {
        epubCacheStore.clear()
        pdfCacheStore.clear()
    }
}
