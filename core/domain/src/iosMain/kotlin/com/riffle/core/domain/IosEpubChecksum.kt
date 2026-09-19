package com.riffle.core.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.posix.uint8_tVar

/**
 * iOS port of jvmMain's [EpubChecksum]: the SHA-256 that keys a cross-EPUB index entry (ADR 0023).
 *
 * Must produce the identical hex digest to the JVM version for the same bytes — the checksum pair
 * is the cache key, so a divergence would silently invalidate every index built on the other
 * platform. Uses CommonCrypto's CC_SHA256 rather than java.security.MessageDigest.
 */
@OptIn(ExperimentalForeignApi::class)
object IosEpubChecksum {

    fun of(bytes: ByteArray): String = memScoped {
        val digest = allocArray<uint8_tVar>(DIGEST_LENGTH)
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, digest)
        } else {
            bytes.usePinned { pinned -> CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), digest) }
        }
        buildString(DIGEST_LENGTH * 2) {
            for (i in 0 until DIGEST_LENGTH) {
                val byte = digest[i].toInt() and 0xFF
                append(HEX[byte shr 4])
                append(HEX[byte and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"

    private val DIGEST_LENGTH = CC_SHA256_DIGEST_LENGTH.toInt()
}
