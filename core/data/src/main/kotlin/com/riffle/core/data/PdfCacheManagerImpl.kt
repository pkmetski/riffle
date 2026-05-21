package com.riffle.core.data

import com.riffle.core.domain.PdfCacheManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfCacheManagerImpl(private val cacheDir: File) : PdfCacheManager {

    override fun getCachedPdf(itemId: String): File? =
        cacheDir.resolve("$itemId.pdf").takeIf { it.exists() }

    override suspend fun cachePdf(itemId: String, stream: InputStream): File =
        withContext(Dispatchers.IO) {
            val dest = cacheDir.resolve("$itemId.pdf")
            val tmp = cacheDir.resolve("$itemId.pdf.tmp")
            try {
                tmp.outputStream().use { out -> stream.copyTo(out) }
                tmp.renameTo(dest)
                dest
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

    override fun evictCachedPdf(itemId: String) {
        cacheDir.resolve("$itemId.pdf").delete()
        cacheDir.resolve("$itemId.pdf.tmp").delete()
    }

    override fun evictAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
