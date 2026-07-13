package com.riffle.core.domain

/**
 * Durable, per-(sourceId, itemId) store of a reading/listening position [payload] plus the wall-clock
 * timestamp it was last set at. The timestamp drives last-update-wins reconciliation against the
 * server. Room cannot share generic entities/DAOs, so each medium keeps its own table; this contract
 * and the [TimestampedPositionStore] base are the shared layer.
 */
interface PositionStore<P> {
    suspend fun save(sourceId: String, itemId: String, payload: P)
    suspend fun load(sourceId: String, itemId: String): P?
    suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long

    /**
     * The last server-side stamp we adopted for this row (via LocalWins push or ServerWins pull).
     * A row is **clean** when `localUpdatedAt == lastSyncedAt` — nothing has changed locally since
     * the last time we synced with the server. Live sync-cycles use this to distinguish "the
     * server has new changes we haven't seen" from "our clock is skewed forward vs the server's".
     * Missing rows return 0.
     */
    suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long

    suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long)

    /**
     * Adopt a server position atomically: writes [payload] AND sets BOTH `localUpdatedAt` and
     * `lastSyncedAt` to [serverStamp], leaving the row **clean**. Used by the live sync cycle on
     * ServerWins so a subsequent reader-close `save()` with a stale in-memory locator can be
     * detected as not-a-real-edit (`payload == existing` skip) and doesn't push it back over the
     * fresh server value. See [SyncPositionStore.acceptServerPosition] for the durable-sweep
     * equivalent that carries a compare-and-swap guard (#528).
     */
    suspend fun acceptServer(sourceId: String, itemId: String, payload: P, serverStamp: Long)
}
