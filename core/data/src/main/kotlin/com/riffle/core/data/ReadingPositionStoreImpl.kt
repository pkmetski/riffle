package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.ReadingPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
) : ReadingPositionStore {

    override suspend fun save(serverId: String, itemId: String, cfi: String) {
        dao.upsert(
            ReadingPositionEntity(
                serverId = serverId,
                itemId = itemId,
                cfi = cfi,
                localUpdatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun load(serverId: String, itemId: String): String? =
        dao.getByItemId(serverId, itemId)?.cfi

    override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long =
        dao.getByItemId(serverId, itemId)?.localUpdatedAt ?: 0L

    override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
        // Use upsert so that a row is always created — the UPDATE-only query silently does nothing
        // when no row exists yet, leaving localUpdatedAt = 0 and causing the server to win every
        // subsequent sync cycle until the user has moved enough to trigger a position save.
        val existing = dao.getByItemId(serverId, itemId)
        dao.upsert(
            ReadingPositionEntity(
                serverId = serverId,
                itemId = itemId,
                cfi = existing?.cfi ?: "",
                localUpdatedAt = millis,
            )
        )
    }
}
