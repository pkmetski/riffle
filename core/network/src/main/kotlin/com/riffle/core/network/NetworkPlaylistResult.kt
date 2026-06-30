package com.riffle.core.network

data class NetworkPlaylist(
    val id: String,
    val libraryId: String,
    val name: String,
    val items: List<NetworkLibraryItem>,
    val bookIds: Set<String>,
) {
    val bookCount: Int get() = bookIds.size
}