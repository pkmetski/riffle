package com.riffle.core.domain

data class LibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val readingProgress: Float,
    val isCached: Boolean,
    val isDownloaded: Boolean,
    val ebookFormat: EbookFormat,
    val ebookFileIno: String? = null,
    // True when the ABS item carries audio (audiobook or combined item). Drives which matched
    // ABS item receives audiobook `currentTime` progress for a readaloud (ADR 0019).
    val hasAudio: Boolean = false,
    // Total audio length in seconds (0 when no audio). Sent with audiobook progress so ABS reports
    // a real percentage rather than 0% on the first sync.
    val audioDurationSec: Double = 0.0,
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val lastOpenedAt: Long? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
    // The owning Server. Item ids are only unique within a Server (ADR 0025), so callers that key
    // local files / DB rows must pair id with serverId. Defaulted for construction sites (e.g.
    // tests) that don't care; the real value is set when mapping from the DB entity.
    val serverId: String = "",
) {
    /** Has an ebook file Riffle can open in the reader (EPUB or PDF). */
    val isReadable: Boolean get() = ebookFormat != EbookFormat.Unsupported

    /** Has audio Riffle can play in the audiobook player — an Audiobook (ADR 0029). */
    val isListenable: Boolean get() = hasAudio

    /**
     * The item has at least one thing Riffle can open — readable or listenable. Replaces the old
     * `isSupported`, which conflated "has an ebook" with "is openable at all"; audiobook-only items
     * are now openable (they Listen), so library surfaces gate on this, while ebook-target selection
     * gates specifically on [isReadable].
     */
    val isPlayable: Boolean get() = isReadable || isListenable
}
