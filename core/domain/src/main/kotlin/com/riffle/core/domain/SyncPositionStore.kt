package com.riffle.core.domain

/** A point-in-time read of a durable per-(serverId, itemId) position row (ADR 0030). */
data class PositionSnapshot<P>(
    val position: P?,
    val localUpdatedAt: Long,
    val lastSyncedAt: Long,
)

/**
 * The durable per-target position store the reconcile reads and writes (ADR 0030). A row is **dirty**
 * when `localUpdatedAt > lastSyncedAt`. All mutating operations are **compare-and-clear**: they apply
 * only if `localUpdatedAt` still equals [ifLocalUpdatedAt] (the value captured at the start of the
 * reconcile), and return `false` when a concurrent local edit advanced it mid-flight — so a fresh
 * offline edit is never clobbered or silently dropped.
 */
interface SyncPositionStore<P> {
    suspend fun snapshot(serverId: String, itemId: String): PositionSnapshot<P>

    /** Server wins: overwrite the local position and set both timestamps to the server stamp (clean). */
    suspend fun acceptServerPosition(
        serverId: String,
        itemId: String,
        position: P,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
    ): Boolean

    /** Local push confirmed: adopt the server-returned stamp into both timestamps (clean). */
    suspend fun confirmPushed(
        serverId: String,
        itemId: String,
        serverStamp: Long,
        ifLocalUpdatedAt: Long,
    ): Boolean

    /** Already in sync (equal stamps): clear dirty by setting `lastSyncedAt = localUpdatedAt`. */
    suspend fun confirmInSync(
        serverId: String,
        itemId: String,
        ifLocalUpdatedAt: Long,
    ): Boolean
}
