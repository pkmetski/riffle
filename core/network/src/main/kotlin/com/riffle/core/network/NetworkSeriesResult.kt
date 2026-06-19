package com.riffle.core.network

import com.riffle.core.domain.EbookFormat

data class NetworkSeriesItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val sequence: String?,
    val readingProgress: Float?,
    val ebookFormat: EbookFormat,
    val ebookFileIno: String? = null,
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val updatedAt: Long? = null,
) {
    val isSupported: Boolean get() = ebookFormat != EbookFormat.Unsupported
}

data class NetworkSeries(
    val id: String,
    val libraryId: String,
    val name: String,
    val items: List<NetworkSeriesItem>,
) {
    val bookCount: Int get() = items.size
}

sealed class NetworkSeriesResult {
    data class Success(val series: List<NetworkSeries>) : NetworkSeriesResult()
    data class NetworkError(val cause: Throwable) : NetworkSeriesResult()
}
