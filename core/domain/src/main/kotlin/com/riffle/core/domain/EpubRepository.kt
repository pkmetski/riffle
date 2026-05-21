package com.riffle.core.domain

import java.io.File

sealed class EpubOpenResult {
    data class Success(val epubFile: File, val lastPosition: String?) : EpubOpenResult()
    data class NetworkError(val cause: Throwable) : EpubOpenResult()
    data object Offline : EpubOpenResult()
}

interface EpubRepository {
    suspend fun openEpub(item: LibraryItem): EpubOpenResult
    suspend fun saveReadingPosition(itemId: String, cfi: String)
}
