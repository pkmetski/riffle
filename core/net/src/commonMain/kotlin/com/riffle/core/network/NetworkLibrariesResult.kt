package com.riffle.core.network

data class NetworkLibrary(
    val id: String,
    val name: String,
    val mediaType: String,
    val audiobooksOnly: Boolean,
    val folders: List<NetworkLibraryFolder> = emptyList(),
)

data class NetworkLibraryFolder(
    val id: String,
    val fullPath: String,
)
