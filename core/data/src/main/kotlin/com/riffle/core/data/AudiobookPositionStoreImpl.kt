package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.SyncPositionStore
import javax.inject.Inject

class AudiobookPositionStoreImpl @Inject constructor(
    private val dao: AudiobookPositionDao,
) : TimestampedPositionStore<Double>(), AudiobookPositionStore, SyncPositionStore<Double> {

    override suspend fun writePayload(serverId: String, itemId: String, payload: Double, updatedAt: Long) {
        // Preserve lastSyncedAt so a local save marks the row dirty (ADR 0030).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(AudiobookPositionEntity(serverId, itemId, payload, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    override suspend fun readPayload(serverId: String, itemId: String): Double? =
        dao.getByItemId(serverId, itemId)?.positionSec

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert so a stamp before the first save still creates a row (see ReadingPositionStoreImpl).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(AudiobookPositionEntity(serverId, itemId, existing?.positionSec ?: 0.0, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    // --- SyncPositionStore (ADR 0030) ---

    override suspend fun snapshot(serverId: String, itemId: String): PositionSnapshot<Double> {
        val e = dao.getByItemId(serverId, itemId)
        return PositionSnapshot(e?.positionSec, e?.localUpdatedAt ?: 0L, e?.lastSyncedAt ?: 0L)
    }

    override suspend fun acceptServerPosition(
        serverId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean {
        if (dao.acceptServerIfUnchanged(serverId, itemId, position, serverStamp, ifLocalUpdatedAt) > 0) return true
        if (dao.getByItemId(serverId, itemId) == null) {
            dao.upsert(AudiobookPositionEntity(serverId, itemId, position, serverStamp, serverStamp))
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
        serverId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long,
    ) {
        dao.upsert(AudiobookPositionEntity(serverId, itemId, position, localUpdatedAt, lastSyncedAt))
    }
}
