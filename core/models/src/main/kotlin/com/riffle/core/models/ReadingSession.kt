package com.riffle.core.models

data class SessionPayload(
    val ebookLocation: String,
    val ebookProgress: Float,
)

sealed class SyncSessionResult {
    data object Success : SyncSessionResult()
    data class NetworkError(val cause: Throwable) : SyncSessionResult()
}

data class ServerProgress(
    val ebookLocation: String,
    val ebookProgress: Float = 0f,
    val lastUpdate: Long,
)

sealed class ProgressSyncCycleResult {
    data class ServerWins(val serverProgress: ServerProgress) : ProgressSyncCycleResult()
    data object LocalWins : ProgressSyncCycleResult()
    data object InSync : ProgressSyncCycleResult()
    data object Offline : ProgressSyncCycleResult()
}
