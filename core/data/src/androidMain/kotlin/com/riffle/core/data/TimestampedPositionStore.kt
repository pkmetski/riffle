package com.riffle.core.data

import com.riffle.core.common.Clock
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
abstract class TimestampedPositionStore<P>(
    private val clock: Clock,
) : PositionStore<P> {

    protected open fun now(): Long = clock.nowMs()

    protected abstract suspend fun writePayload(sourceId: String, itemId: String, payload: P, updatedAt: Long)
    /** Overwrite the row with [payload] and set BOTH `localUpdatedAt` and `lastSyncedAt` to [stamp]. */
    protected abstract suspend fun writeCleanAtStamp(sourceId: String, itemId: String, payload: P, stamp: Long)
    /** Set BOTH `localUpdatedAt` and `lastSyncedAt` to [stamp] without touching the payload. */
    protected abstract suspend fun writeStampsOnly(sourceId: String, itemId: String, stamp: Long)
    protected abstract suspend fun readPayload(sourceId: String, itemId: String): P?
    protected abstract suspend fun readUpdatedAt(sourceId: String, itemId: String): Long?
    protected abstract suspend fun readLastSyncedAt(sourceId: String, itemId: String): Long?
    protected abstract suspend fun writeUpdatedAt(sourceId: String, itemId: String, updatedAt: Long)

    final override suspend fun save(sourceId: String, itemId: String, payload: P) {
        // Idempotent: if the stored payload already equals the incoming one, this is a no-op
        // instead of a fresh timestamp bump. Protects the "reader-close race" where the reader's
        // onClose fires with a stale in-memory locator (before the ServerWins UI-jump landed);
        // the stale save would otherwise re-stamp the row with maxOf(now, existing+1) and the
        // next sync-cycle would push the stale local back over the fresh server value (#528).
        if (readPayload(sourceId, itemId) == payload) return
        val existing = readUpdatedAt(sourceId, itemId) ?: 0L
        val ts = maxOf(now(), existing + 1)
        writePayload(sourceId, itemId, payload, ts)
    }

    final override suspend fun acceptServer(sourceId: String, itemId: String, payload: P, serverStamp: Long) {
        writeCleanAtStamp(sourceId, itemId, payload, serverStamp)
    }

    final override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {
        writeStampsOnly(sourceId, itemId, stamp)
    }

    final override suspend fun load(sourceId: String, itemId: String): P? =
        readPayload(sourceId, itemId)

    final override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long =
        readUpdatedAt(sourceId, itemId) ?: 0L

    final override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long =
        readLastSyncedAt(sourceId, itemId) ?: 0L

    final override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) =
        writeUpdatedAt(sourceId, itemId, millis)
}
