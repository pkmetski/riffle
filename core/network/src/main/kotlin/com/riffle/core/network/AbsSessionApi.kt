package com.riffle.core.network

data class NetworkEbookProgressPayload(
    val ebookLocation: String,
    val ebookProgress: Float,
)

data class NetworkAudiobookProgressPayload(
    val currentTime: Double,
    val duration: Double,
)

data class NetworkServerProgress(
    val ebookLocation: String,
    val ebookProgress: Float = 0f,
    // Audiobook progress from the same ABS media-progress record.
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val lastUpdate: Long,
)

sealed class NetworkSyncSessionResult {
    data class Success(val lastUpdate: Long) : NetworkSyncSessionResult()
    data class NetworkError(val cause: Throwable) : NetworkSyncSessionResult()
}

sealed class NetworkGetProgressResult {
    data class Success(val progress: NetworkServerProgress) : NetworkGetProgressResult()
    data class NetworkError(val cause: Throwable) : NetworkGetProgressResult()
}

interface AbsSessionApi {
    suspend fun syncEbookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkEbookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSyncSessionResult

    suspend fun syncAudiobookProgress(
        baseUrl: String,
        libraryItemId: String,
        payload: NetworkAudiobookProgressPayload,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkSyncSessionResult

    suspend fun getProgress(
        baseUrl: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkGetProgressResult
}
