package com.riffle.core.domain

/**
 * Durable, per-(serverId, itemId) store of a reading/listening position [payload] plus the wall-clock
 * timestamp it was last set at. The timestamp drives last-update-wins reconciliation against the
 * server. Room cannot share generic entities/DAOs, so each medium keeps its own table; this contract
 * and the [TimestampedPositionStore] base are the shared layer.
 */
interface PositionStore<P> {
    suspend fun save(serverId: String, itemId: String, payload: P)
    suspend fun load(serverId: String, itemId: String): P?
    suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long
    suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long)
}
