package com.riffle.core.data.localfiles

import java.io.File
import java.io.InputStream

/**
 * Copies book bytes and cover bytes into Riffle-owned private storage. Abstracted so JVM tests
 * can substitute a fake that writes to a temp dir.
 */
interface CopyInService {
    /** Copy [stream] into private storage, returning the absolute path of the written file. */
    suspend fun copyBook(sourceId: String, sourceItemId: String, extension: String, stream: InputStream): File

    /** Write [bytes] as a cover image, returning the absolute path. */
    suspend fun writeCover(sourceId: String, sourceItemId: String, extension: String, bytes: ByteArray): File

    /** Delete the copied book bytes for [sourceItemId], if present. */
    suspend fun deleteBook(sourceId: String, sourceItemId: String)

    /** Delete the cover bytes for [sourceItemId], if present. */
    suspend fun deleteCover(sourceId: String, sourceItemId: String)
}
