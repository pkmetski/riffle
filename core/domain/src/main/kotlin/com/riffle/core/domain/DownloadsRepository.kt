package com.riffle.core.domain

interface DownloadsRepository {
    fun getDownloadedItemIds(): List<String>
    fun getCachedItemIds(): List<String>
    suspend fun removeAllDownloads()
    suspend fun clearAllCached()
}
