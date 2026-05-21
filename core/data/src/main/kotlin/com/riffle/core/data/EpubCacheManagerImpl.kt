package com.riffle.core.data

import com.riffle.core.domain.EpubCacheManager
import java.io.File

class EpubCacheManagerImpl(private val cacheDir: File) : EpubCacheManager {

    override fun getCachedEpub(itemId: String): File? =
        cacheDir.resolve("$itemId.epub").takeIf { it.exists() }

    override suspend fun cacheEpub(itemId: String, data: ByteArray): File =
        cacheDir.resolve("$itemId.epub").also { it.writeBytes(data) }

    override fun evictAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
