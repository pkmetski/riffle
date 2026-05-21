package com.riffle.core.network

data class NetworkEbookProgressPayload(
    val ebookLocation: String,
    val ebookProgress: Float,
)

sealed class NetworkSyncSessionResult {
    data object Success : NetworkSyncSessionResult()
    data class NetworkError(val cause: Throwable) : NetworkSyncSessionResult()
}

interface AbsSessionApi {
    suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSyncSessionResult
}
