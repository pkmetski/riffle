package com.riffle.core.domain

import java.io.File
import com.riffle.core.models.LibraryItem

sealed class EpubOpenResult {
    data class Success(
        val epubFile: File,
        val lastPosition: String?,
        val temporary: Boolean = false,
    ) : EpubOpenResult()
    data class NetworkError(val cause: Throwable) : EpubOpenResult()
    data object Offline : EpubOpenResult()
}

interface JvmEpubRepository : EpubRepository {
    suspend fun openEpub(item: LibraryItem): EpubOpenResult
    suspend fun openEpubForMetadata(item: LibraryItem): EpubOpenResult = openEpub(item)
}
