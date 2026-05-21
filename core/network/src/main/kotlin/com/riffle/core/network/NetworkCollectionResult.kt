package com.riffle.core.network

data class NetworkCollection(
    val id: String,
    val libraryId: String,
    val name: String,
    val items: List<NetworkLibraryItem>,
) {
    val bookCount: Int get() = items.size
}

sealed class NetworkCollectionResult {
    data class Success(val collections: List<NetworkCollection>) : NetworkCollectionResult()
    data class NetworkError(val cause: Throwable) : NetworkCollectionResult()
}
