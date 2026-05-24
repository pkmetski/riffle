package com.riffle.core.domain

interface ReadingSessionRepository {
    suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult
    suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult
    suspend fun setProgress(itemId: String, progress: Float)
}
