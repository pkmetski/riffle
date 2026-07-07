package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.SyncPositionStore
import javax.inject.Inject

class AudiobookPositionStoreImpl @Inject constructor(
    private val dao: AudiobookPositionDao,
    clock: Clock,
) : TimestampedPositionStore<Double>(clock), AudiobookPositionStore, SyncPositionStore<Double> {

    override suspend fun writePayload(sourceId: String, itemId: String, payload: Double, updatedAt: Long) {
        // Preserve lastSyncedAt so a local save marks the row dirty (ADR 0030).
        val existing = dao.getByItemId(sourceId, itemId)
        dao.upsert(AudiobookPositionEntity(sourceId, itemId, payload, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    override suspend fun readPayload(sourceId: String, itemId: String): Double? =
        dao.getByItemId(sourceId, itemId)?.positionSec

    override suspend fun readUpdatedAt(sourceId: String, itemId: String): Long? =
        dao.getByItemId(sourceId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(sourceId: String, itemId: String, updatedAt: Long) {
        // upsert so a stamp before the first save still creates a row (see ReadingPositionStoreImpl).
        val existing = dao.getByItemId(sourceId, itemId)
        dao.upsert(AudiobookPositionEntity(sourceId, itemId, existing?.positionSec ?: 0.0, updatedAt, existing?.lastSyncedAt ?: 0L))
    }

    // --- SyncPositionStore (ADR 0030) ---

    override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<Double> {
        val e = dao.getByItemId(sourceId, itemId)
        return PositionSnapshot(e?.positionSec, e?.localUpdatedAt ?: 0L, e?.lastSyncedAt ?: 0L)
    }

    override suspend fun acceptServerPosition(
        sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long,
    ): Boolean {
        if (dao.acceptServerIfUnchanged(sourceId, itemId, position, serverStamp, ifLocalUpdatedAt) > 0) return true
        if (dao.getByItemId(sourceId, itemId) == null) {
            dao.upsert(AudiobookPositionEntity(sourceId, itemId, position, serverStamp, serverStamp))
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
        sourceId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long,
    ) {
        dao.upsert(AudiobookPositionEntity(sourceId, itemId, position, localUpdatedAt, lastSyncedAt))
    }
}
