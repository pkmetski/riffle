package com.riffle.core.data.localfiles

import com.riffle.core.catalog.CatalogFileStream

interface LocalFilesFileReader {
    fun toFileUrl(path: String): String
    suspend fun <T> withFileStream(path: String, sizeBytes: Long?, block: suspend (CatalogFileStream) -> T): T
}
