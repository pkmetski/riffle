package com.riffle.core.domain

import java.security.MessageDigest

/**
 * Content checksum of an EPUB file, used to key the cross-EPUB index cache (ADR 0019).
 * A server re-uploading its EPUB changes the bytes and therefore the checksum, so the
 * keyed cache lookup misses and the next sync cycle rebuilds — no explicit invalidation.
 */
object EpubChecksum {

    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
