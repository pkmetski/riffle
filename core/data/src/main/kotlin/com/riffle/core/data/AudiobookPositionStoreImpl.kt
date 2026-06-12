package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.AudiobookPositionStore
import javax.inject.Inject

class AudiobookPositionStoreImpl @Inject constructor(
    private val dao: AudiobookPositionDao,
) : TimestampedPositionStore<Double>(), AudiobookPositionStore {

    override suspend fun writePayload(serverId: String, itemId: String, payload: Double, updatedAt: Long) {
        dao.upsert(AudiobookPositionEntity(serverId, itemId, payload, updatedAt))
    }

    override suspend fun readPayload(serverId: String, itemId: String): Double? =
        dao.getByItemId(serverId, itemId)?.positionSec

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert so a stamp before the first save still creates a row (see ReadingPositionStoreImpl).
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(AudiobookPositionEntity(serverId, itemId, existing?.positionSec ?: 0.0, updatedAt))
    }
}
