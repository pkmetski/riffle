package com.riffle.core.domain

import java.io.File
import java.io.InputStream

interface EpubCacheManager {
    fun getCachedEpub(itemId: String): File?
    suspend fun cacheEpub(itemId: String, stream: InputStream): File
    fun evictAll()
}
