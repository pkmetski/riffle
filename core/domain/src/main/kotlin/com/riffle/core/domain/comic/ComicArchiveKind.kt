package com.riffle.core.domain.comic

/**
 * Discriminates comic archive formats from magic bytes so we can surface a clean "not supported"
 * message for real RAR files even when they're extension-mislabeled — and open a `.cbr` that's
 * actually a ZIP transparently. See ADR 0042 (move 3).
 */
enum class ComicArchiveKind {
    /** ZIP-based (CBZ, or a `.cbr` that turns out to be a ZIP). Supported in v1. */
    ZIP,

    /** RAR-based. Not supported in v1; UI shows a friendly message and hides the Read button. */
    RAR,

    /** Unrecognised magic. Treat as unsupported. */
    UNKNOWN,
}

object ComicArchiveSniffer {

    // "PK\x03\x04" is the ZIP local-file-header signature.
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    // "Rar!\x1A\x07" is the RAR archive signature (both RAR4 and RAR5 start with these bytes).
    private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    fun sniff(head: ByteArray): ComicArchiveKind = when {
        matches(head, ZIP_MAGIC) -> ComicArchiveKind.ZIP
        matches(head, RAR_MAGIC) -> ComicArchiveKind.RAR
        else -> ComicArchiveKind.UNKNOWN
    }

    private fun matches(head: ByteArray, prefix: ByteArray): Boolean {
        if (head.size < prefix.size) return false
        for (i in prefix.indices) if (head[i] != prefix[i]) return false
        return true
    }
}
