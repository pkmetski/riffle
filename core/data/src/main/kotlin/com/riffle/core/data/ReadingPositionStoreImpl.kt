package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.ReadingPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
) : TimestampedPositionStore<String>(), ReadingPositionStore {

    override suspend fun writePayload(serverId: String, itemId: String, payload: String, updatedAt: Long) {
        dao.upsert(ReadingPositionEntity(serverId, itemId, payload, updatedAt))
    }

    override suspend fun readPayload(serverId: String, itemId: String): String? =
        dao.getByItemId(serverId, itemId)?.cfi

    override suspend fun readUpdatedAt(serverId: String, itemId: String): Long? =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt

    override suspend fun writeUpdatedAt(serverId: String, itemId: String, updatedAt: Long) {
        // upsert (not the UPDATE-only DAO query) so a row is always created — otherwise a stamp before
        // the first position save silently no-ops, leaving localUpdatedAt = 0 and letting the server
        // win every subsequent cycle.
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(ReadingPositionEntity(serverId, itemId, existing?.cfi ?: "", updatedAt))
    }
}
