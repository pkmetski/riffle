package com.riffle.core.network

data class NetworkLibraryItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val readingProgress: Float,
    val isSupported: Boolean,
)

sealed class NetworkLibraryItemsResult {
    data class Success(val items: List<NetworkLibraryItem>) : NetworkLibraryItemsResult()
    data class NetworkError(val cause: Throwable) : NetworkLibraryItemsResult()
}
