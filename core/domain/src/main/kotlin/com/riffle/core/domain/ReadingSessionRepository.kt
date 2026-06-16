package com.riffle.core.domain

interface ReadingSessionRepository {
    suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult
    suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult

    /**
     * Mark the item read/listened (`finished = true`) or unread/unlistened (`finished = false`)
     * on ABS. Resets BOTH dimensions of the shared media-progress record in one PATCH: the ebook
     * (`ebookProgress` → 1 or 0, `ebookLocation` cleared on unread) and the audio (`isFinished`
     * flips `progress`/`currentTime`). Without the audio half, a finished/in-progress audiobook
     * `progress` survives and re-shadows a 0 `ebookProgress` on the next library refresh (the
     * "unread restores old progress" bug). Bumps the local position timestamp so the change syncs.
     *
     * Unread additionally wipes every LOCAL position store for the item (reading position, audiobook
     * position, readaloud resume) so reopening the book can't restore — and then re-save — a stale
     * position from a store the server reset didn't cover.
     */
    suspend fun markFinished(itemId: String, finished: Boolean)

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
