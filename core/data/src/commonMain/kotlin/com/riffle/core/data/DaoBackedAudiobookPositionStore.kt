package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.AudiobookPositionStore

// AudiobookPositionStore backed by any AudiobookPositionDao implementation.
// Applies the same monotonic timestamp guard as DaoBackedReadingPositionStore.
// Used on iOS via IosAudiobookPositionDao; Android uses its own androidMain impl.
internal open class DaoBackedAudiobookPositionStore(
    private val dao: AudiobookPositionDao,
    private val clock: Clock,
) : AudiobookPositionStore {

    override suspend fun save(sourceId: String, itemId: String, payload: Double) {
        val existing = dao.getByItemId(sourceId, itemId)
        if (existing?.positionSec == payload) return // idempotent — no re-stamp
        val now = clock.nowMs()
        val stamp = if (existing != null) maxOf(now, existing.localUpdatedAt + 1) else now
        dao.upsert(
            AudiobookPositionEntity(
                sourceId = sourceId,
                itemId = itemId,
                positionSec = payload,
                localUpdatedAt = stamp,
                lastSyncedAt = existing?.lastSyncedAt ?: stamp,
                deleted = false,
            ),
        )
    }

    override suspend fun load(sourceId: String, itemId: String): Double? =
        dao.getByItemId(sourceId, itemId)?.takeIf { !it.deleted }?.positionSec

    override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long =
        dao.getByItemId(sourceId, itemId)?.localUpdatedAt ?: 0L

    override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long =
        dao.getByItemId(sourceId, itemId)?.lastSyncedAt ?: 0L

    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
        val existing = dao.getByItemId(sourceId, itemId) ?: return
        dao.upsert(existing.copy(localUpdatedAt = millis))
    }

    override suspend fun acceptServer(
        sourceId: String,
        itemId: String,
        payload: Double,
        serverStamp: Long,
    ) {
        val existing = dao.getByItemId(sourceId, itemId)
        dao.acceptServerIfUnchanged(
            sourceId = sourceId,
            itemId = itemId,
            positionSec = payload,
            serverStamp = serverStamp,
            ifLocalUpdatedAt = existing?.localUpdatedAt ?: 0L,
            deleted = false,
        )
    }

    override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {
        val existing = dao.getByItemId(sourceId, itemId) ?: return
        dao.upsert(existing.copy(localUpdatedAt = stamp, lastSyncedAt = stamp))
    }
}
