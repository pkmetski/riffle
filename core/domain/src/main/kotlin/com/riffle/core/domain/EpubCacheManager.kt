package com.riffle.core.domain

import java.io.File

interface EpubCacheManager {
    fun getCachedEpub(itemId: String): File?
    suspend fun cacheEpub(itemId: String, data: ByteArray): File
    fun evictAll()
}
