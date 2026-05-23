package com.riffle.core.data

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalStore

class DownloadsRepositoryImpl(
    private val epubCacheStore: LocalStore,
    private val epubDownloadsStore: LocalStore,
    private val pdfCacheStore: LocalStore,
    private val pdfDownloadsStore: LocalStore,
) : DownloadsRepository {

    override fun getDownloadedItemIds(): List<String> =
        (epubDownloadsStore.listItemIds() + pdfDownloadsStore.listItemIds()).distinct()

    override fun getCachedItemIds(): List<String> {
        val downloadedIds = getDownloadedItemIds().toHashSet()
        return (epubCacheStore.listItemIds() + pdfCacheStore.listItemIds())
            .distinct()
            .filter { it !in downloadedIds }
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
