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
    val isSupported: Boolean get() = ebookFormat != EbookFormat.Unsupported
}
