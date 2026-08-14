package com.riffle.core.network

import com.riffle.core.models.EbookFormat

data class NetworkLibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val readingProgress: Float?,
    val ebookFormat: EbookFormat,
    val ebookFileIno: String? = null,
    val hasAudio: Boolean = false,
    val audioDurationSec: Double = 0.0,
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
    /** ABS's stable on-disk item path, when the endpoint includes it. */
    val path: String? = null,
    val relPath: String? = null,
) {
    val isSupported: Boolean get() = ebookFormat != EbookFormat.Unsupported
}
