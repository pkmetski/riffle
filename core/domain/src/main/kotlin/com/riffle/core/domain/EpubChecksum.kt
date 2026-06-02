package com.riffle.core.domain

import java.io.File
import java.security.MessageDigest

/**
 * Content checksum of an EPUB file, used to key the cross-EPUB index cache (ADR 0019).
 * A server re-uploading its EPUB changes the bytes and therefore the checksum, so the
 * keyed cache lookup misses and the next sync cycle rebuilds — no explicit invalidation.
 */
object EpubChecksum {

    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * Streams the file through the digest so a hundreds-of-MB synced bundle (ADR 0023) is hashed
     * without ever being held in memory — produces the same value as [of] over the same bytes.
     */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
