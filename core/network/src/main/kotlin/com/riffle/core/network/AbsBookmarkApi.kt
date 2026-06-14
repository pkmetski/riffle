package com.riffle.core.network

data class NetworkAbsBookmark(
    val libraryItemId: String,
    val title: String,
    val timeSec: Int,
    val createdAt: Long,
)

sealed interface AbsBookmarkResult {
    data class Success(val bookmark: NetworkAbsBookmark) : AbsBookmarkResult
    data class NetworkError(val cause: Throwable) : AbsBookmarkResult
}

sealed interface AbsBookmarkListResult {
    data class Success(val bookmarks: List<NetworkAbsBookmark>) : AbsBookmarkListResult
    data class NetworkError(val cause: Throwable) : AbsBookmarkListResult
}

interface AbsBookmarkApi {
    suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult

    suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult

    suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkResult

    suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): AbsBookmarkListResult
}
