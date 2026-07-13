package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.Clock
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SyncPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
    clock: Clock,
) : TimestampedPositionStore<String>(clock), ReadingPositionStore, SyncPositionStore<String> {

    override suspend fun writePayload(sourceId: String, itemId: String, payload: String, updatedAt: Long) {
        // Preserve lastSyncedAt so a local save marks the row dirty (localUpdatedAt > lastSyncedAt)
        // rather than silently clearing the sync marker (ADR 0030).
        val existing = dao.getByItemId(sourceId, itemId)
        dao.upsert(ReadingPositionEntity(sourceId, itemId, payload, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    override suspend fun writeCleanAtStamp(sourceId: String, itemId: String, payload: String, stamp: Long) {
        dao.upsert(ReadingPositionEntity(sourceId, itemId, payload, stamp, stamp))
    }

    override suspend fun readPayload(sourceId: String, itemId: String): String? =
        dao.getByItemId(sourceId, itemId)?.cfi

    override suspend fun readUpdatedAt(sourceId: String, itemId: String): Long? =
        dao.getByItemId(sourceId, itemId)?.localUpdatedAt

    override suspend fun readLastSyncedAt(sourceId: String, itemId: String): Long? =
        dao.getByItemId(sourceId, itemId)?.lastSyncedAt

    override suspend fun writeUpdatedAt(sourceId: String, itemId: String, updatedAt: Long) {
        // upsert (not the UPDATE-only DAO query) so a row is always created — otherwise a stamp before
        // the first position save silently no-ops, leaving localUpdatedAt = 0 and letting the server
        // win every subsequent cycle. lastSyncedAt is preserved (see writePayload).
        val existing = dao.getByItemId(sourceId, itemId)
        dao.upsert(ReadingPositionEntity(sourceId, itemId, existing?.cfi ?: "", updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    // --- SyncPositionStore (ADR 0030) ---

    override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<String> {
        val e = dao.getByItemId(sourceId, itemId)
        return PositionSnapshot(e?.cfi, e?.localUpdatedAt ?: 0L, e?.lastSyncedAt ?: 0L)
    }

    override suspend fun acceptServerPosition(
        sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean {
        if (dao.acceptServerIfUnchanged(sourceId, itemId, position, serverStamp, ifLocalUpdatedAt) > 0) return true
        // No matching row updated: either the row is absent (create it — a server win on a fresh row)
        // or localUpdatedAt advanced mid-flight (superseded — leave the fresh local edit alone).
        if (dao.getByItemId(sourceId, itemId) == null) {
            dao.upsert(ReadingPositionEntity(sourceId, itemId, position, serverStamp, serverStamp))
            return true
        }
        return false
    }

    override suspend fun confirmPushed(
        sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean = dao.confirmPushedIfUnchanged(sourceId, itemId, serverStamp, ifLocalUpdatedAt) > 0

    override suspend fun confirmInSync(
        sourceId: String, itemId: String, ifLocalUpdatedAt: Long,
    ): Boolean = dao.confirmInSyncIfUnchanged(sourceId, itemId, ifLocalUpdatedAt) > 0

    override suspend fun mirror(
        sourceId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long,
    ) {
        dao.upsert(ReadingPositionEntity(sourceId, itemId, position, localUpdatedAt, lastSyncedAt))
    }
}
