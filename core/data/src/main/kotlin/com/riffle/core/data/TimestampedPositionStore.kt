package com.riffle.core.data

import com.riffle.core.domain.PositionStore

/**
 * Shared policy for the per-medium position stores: [save] stamps a monotonically-non-decreasing
 * timestamp (wall clock, or one millisecond past the stored value when the wall clock is behind);
 * [loadLocalUpdatedAt] defaults a missing row to 0L. Subclasses supply the per-table storage via the
 * four template methods (Room forbids a shared generic entity/DAO). [now] is overridable for tests.
 *
 * The monotonic guard exists because the live progress-sync cycle and the durable sweep order edits
 * by comparing `localUpdatedAt` with the server's `lastUpdate`. ABS stamps PATCHes with its own wall
 * clock; when that clock is ahead of the device's clock, the cycle adopts the server's future stamp
 * into `localUpdatedAt` (so the write doesn't immediately read back as a "newer remote"). If the
 * very next `save()` used a raw `now()`, it would silently regress `localUpdatedAt` below the adopted
 * stamp, making every subsequent cycle conclude server-wins and yank the reader back to the older
 * server position — the "periodic sync overwrites my position" bug. Saving `max(now, existing+1)`
 * preserves user edits as strictly newer than any prior state, regardless of cross-machine clock skew.
 */
abstract class TimestampedPositionStore<P> : PositionStore<P> {

    protected open fun now(): Long = System.currentTimeMillis()

    protected abstract suspend fun writePayload(serverId: String, itemId: String, payload: P, updatedAt: Long)
    protected abstract suspend fun readPayload(serverId: String, itemId: String): P?
    protected abstract suspend fun readUpdatedAt(serverId: String, itemId: String): Long?
    protected abstract suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long)

    final override suspend fun save(serverId: String, itemId: String, payload: P) {
        val existing = readUpdatedAt(serverId, itemId) ?: 0L
        val ts = maxOf(now(), existing + 1)
        writePayload(serverId, itemId, payload, ts)
    }

    final override suspend fun load(serverId: String, itemId: String): P? =
        readPayload(serverId, itemId)

    final override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long =
        readUpdatedAt(serverId, itemId) ?: 0L

    final override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) =
        writeUpdatedAt(serverId, itemId, millis)
}
