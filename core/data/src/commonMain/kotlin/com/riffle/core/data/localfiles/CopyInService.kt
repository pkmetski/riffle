package com.riffle.core.data.localfiles

import okio.Source

// Copies book and cover bytes into Riffle-owned private storage; returns absolute on-disk paths.
interface CopyInService {
    suspend fun copyBook(sourceId: String, sourceItemId: String, extension: String, source: Source): String

    suspend fun writeCover(sourceId: String, sourceItemId: String, extension: String, bytes: ByteArray): String

    suspend fun deleteBook(sourceId: String, sourceItemId: String)

    suspend fun deleteCover(sourceId: String, sourceItemId: String)
}
