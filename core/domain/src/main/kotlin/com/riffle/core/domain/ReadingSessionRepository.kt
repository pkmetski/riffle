package com.riffle.core.domain

interface ReadingSessionRepository {
    suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult
    suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult
    suspend fun setProgress(itemId: String, progress: Float)

    /**
     * Notify the ABS server that this user has just (re-)opened the item — used to drive the
     * cross-device "In Progress" sort. Reads the server's current `mediaProgress` for the item
     * and PATCHes back the same `ebookLocation` + `ebookProgress`, which bumps the server-side
     * `lastUpdate` without ever clobbering a position another device may have advanced. Best
     * effort: if offline or no active server, the call is a no-op — the local `lastOpenedAt`
     * stamp survives and lifts the server timestamp via max() on the next successful refresh.
     */
    suspend fun touchOpenTimestamp(itemId: String)
}
