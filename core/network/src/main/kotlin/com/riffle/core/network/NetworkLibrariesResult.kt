package com.riffle.core.network

data class NetworkLibrary(
    val id: String,
    val name: String,
    val mediaType: String,
)

sealed class NetworkLibrariesResult {
    data class Success(val libraries: List<NetworkLibrary>) : NetworkLibrariesResult()
    data class NetworkError(val cause: Throwable) : NetworkLibrariesResult()
}
