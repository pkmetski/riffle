package com.riffle.core.domain

import java.io.File
import java.io.InputStream

interface PdfCacheManager {
    fun getCachedPdf(itemId: String): File?
    suspend fun cachePdf(itemId: String, stream: InputStream): File
    fun evictCachedPdf(itemId: String)
    fun evictAll()
}
