package com.riffle.core.data

import com.riffle.core.domain.EpubCacheManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EpubCacheManagerImpl(private val cacheDir: File) : EpubCacheManager {

    override fun getCachedEpub(itemId: String): File? =
        cacheDir.resolve("$itemId.epub").takeIf { it.exists() }

    override suspend fun cacheEpub(itemId: String, stream: InputStream): File =
        withContext(Dispatchers.IO) {
            val dest = cacheDir.resolve("$itemId.epub")
            val tmp = cacheDir.resolve("$itemId.epub.tmp")
            try {
                tmp.outputStream().use { out -> stream.copyTo(out) }
                tmp.renameTo(dest)
                dest
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

    override fun evictAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
