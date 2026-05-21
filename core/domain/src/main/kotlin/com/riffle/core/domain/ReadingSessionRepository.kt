package com.riffle.core.domain

interface ReadingSessionRepository {
    suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult
}
