package com.riffle.core.data.localfiles

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.use

// Content identity = SHA-1 of (first 64KB prefix + ASCII-decimal size). It is the storage key for every
// ingested library, so the digested bytes MUST stay byte-identical — any change re-ingests everyone's files.
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
