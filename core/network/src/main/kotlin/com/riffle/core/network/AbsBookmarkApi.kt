package com.riffle.core.network

data class NetworkAbsBookmark(
    val libraryItemId: String,
    val title: String,
    val timeSec: Int,
    val createdAt: Long,
)

interface AbsBookmarkApi {
    suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark>

    suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark>

    suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark>

    suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>>
}
