package com.riffle.core.network

data class NetworkSeriesItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val sequence: String?,
    val readingProgress: Float,
    val isSupported: Boolean,
)

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
