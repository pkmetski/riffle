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
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val lastOpenedAt: Long? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
) {
    val isSupported: Boolean get() = ebookFormat != EbookFormat.Unsupported
}
