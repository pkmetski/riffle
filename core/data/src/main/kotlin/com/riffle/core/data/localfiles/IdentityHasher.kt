package com.riffle.core.data.localfiles

import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-based identity for an ingested file — SHA-1 of the first 64KB combined with the file
 * size. Cheap (single read of the prefix), stable across path changes and renames, tolerant of
 * SAF re-mounts. Full-file hashing would be prohibitive for large PDFs; a prefix hash is enough
 * to distinguish user libraries in practice.
 */
object IdentityHasher {

    private const val PREFIX_BYTES = 64 * 1024

    fun hash(prefix: ByteArray, sizeBytes: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(prefix, 0, minOf(prefix.size, PREFIX_BYTES))
        md.update(sizeBytes.toString().toByteArray(Charsets.US_ASCII))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads up to [PREFIX_BYTES] from [input] and hashes with [sizeBytes]. */
    fun hash(input: InputStream, sizeBytes: Long): String {
        val buf = ByteArray(PREFIX_BYTES)
        var total = 0
        while (total < buf.size) {
            val read = input.read(buf, total, buf.size - total)
            if (read <= 0) break
            total += read
        }
        return hash(if (total == buf.size) buf else buf.copyOf(total), sizeBytes)
    }
}
