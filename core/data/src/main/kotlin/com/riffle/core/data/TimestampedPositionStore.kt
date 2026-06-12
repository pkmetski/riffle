package com.riffle.core.data

import com.riffle.core.domain.PositionStore

/**
 * Shared policy for the per-medium position stores: [save] stamps the current wall clock;
 * [loadLocalUpdatedAt] defaults a missing row to 0L. Subclasses supply the per-table storage via the
 * four template methods (Room forbids a shared generic entity/DAO). [now] is overridable for tests.
 */
abstract class TimestampedPositionStore<P> : PositionStore<P> {

    protected open fun now(): Long = System.currentTimeMillis()

    protected abstract suspend fun writePayload(serverId: String, itemId: String, payload: P, updatedAt: Long)
    protected abstract suspend fun readPayload(serverId: String, itemId: String): P?
    protected abstract suspend fun readUpdatedAt(serverId: String, itemId: String): Long?
    protected abstract suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long)

    final override suspend fun save(serverId: String, itemId: String, payload: P) =
        writePayload(serverId, itemId, payload, now())

    final override suspend fun load(serverId: String, itemId: String): P? =
        readPayload(serverId, itemId)

    final override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long =
        readUpdatedAt(serverId, itemId) ?: 0L

    final override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) =
        writeUpdatedAt(serverId, itemId, millis)
}
