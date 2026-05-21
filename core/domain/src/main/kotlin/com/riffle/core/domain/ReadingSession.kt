package com.riffle.core.domain

data class SessionPayload(
    val ebookLocation: String,
    val ebookProgress: Float,
)

sealed class SyncSessionResult {
    data object Success : SyncSessionResult()
    data class NetworkError(val cause: Throwable) : SyncSessionResult()
}
