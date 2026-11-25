package com.riffle.core.data.localfiles

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.use

/**
 * Content-based identity for an ingested file — SHA-1 of the first 64KB combined with the file
 * size. Cheap (single read of the prefix), stable across path changes and renames.
 *
 * The hash is a storage key for every ingested user library — changing it re-ingests everything,
 * so the digested bytes (prefix slice + ASCII-decimal size) must stay byte-identical.
 */
object IdentityHasher {

    private const val PREFIX_BYTES = 64 * 1024

    fun hash(prefix: ByteArray, sizeBytes: Long): String {
        val slice = if (prefix.size <= PREFIX_BYTES) prefix else prefix.copyOf(PREFIX_BYTES)
        val sizeLabel = sizeBytes.toString().encodeToByteArray()
        return (slice + sizeLabel).toByteString().sha1().hex()
    }

    suspend fun hash(source: Source, sizeBytes: Long): String =
        hash(readPrefix(source), sizeBytes)

    private suspend fun readPrefix(source: Source): ByteArray = source.use { s ->
        val buffer = Buffer()
        var total = 0L
        while (total < PREFIX_BYTES) {
            val read = s.read(buffer, PREFIX_BYTES - total)
            if (read == -1L) break
            total += read
        }
        buffer.readByteArray()
    }
}
