package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SyncPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
) : TimestampedPositionStore<String>(), ReadingPositionStore, SyncPositionStore<String> {

    override suspend fun writePayload(serverId: String, itemId: String, payload: String, updatedAt: Long) {
        // Preserve lastSyncedAt so a local save marks the row dirty (localUpdatedAt > lastSyncedAt)
        // rather than silently clearing the sync marker (ADR 0030).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(ReadingPositionEntity(serverId, itemId, payload, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    override suspend fun readPayload(serverId: String, itemId: String): String? =
        dao.getByItemId(serverId, itemId)?.cfi

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert (not the UPDATE-only DAO query) so a row is always created — otherwise a stamp before
        // the first position save silently no-ops, leaving localUpdatedAt = 0 and letting the server
        // win every subsequent cycle. lastSyncedAt is preserved (see writePayload).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(ReadingPositionEntity(serverId, itemId, existing?.cfi ?: "", updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    // --- SyncPositionStore (ADR 0030) ---

    override suspend fun snapshot(serverId: String, itemId: String): PositionSnapshot<String> {
        val e = dao.getByItemId(serverId, itemId)
        return PositionSnapshot(e?.cfi, e?.localUpdatedAt ?: 0L, e?.lastSyncedAt ?: 0L)
    }

    override suspend fun acceptServerPosition(
        serverId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean {
        if (dao.acceptServerIfUnchanged(serverId, itemId, position, serverStamp, ifLocalUpdatedAt) > 0) return true
        // No matching row updated: either the row is absent (create it — a server win on a fresh row)
        // or localUpdatedAt advanced mid-flight (superseded — leave the fresh local edit alone).
        if (dao.getByItemId(serverId, itemId) == null) {
            dao.upsert(ReadingPositionEntity(serverId, itemId, position, serverStamp, serverStamp))
            return true
        }
        return false
    }

    override suspend fun confirmPushed(
        serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean = dao.confirmPushedIfUnchanged(serverId, itemId, serverStamp, ifLocalUpdatedAt) > 0

    override suspend fun confirmInSync(
        serverId: String, itemId: String, ifLocalUpdatedAt: Long,
    ): Boolean = dao.confirmInSyncIfUnchanged(serverId, itemId, ifLocalUpdatedAt) > 0

    override suspend fun mirror(
        serverId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long,
    ) {
        dao.upsert(ReadingPositionEntity(serverId, itemId, position, localUpdatedAt, lastSyncedAt))
    }
}
