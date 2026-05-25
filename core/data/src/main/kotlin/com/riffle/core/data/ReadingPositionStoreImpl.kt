package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.ReadingPositionStore
import javax.inject.Inject

class ReadingPositionStoreImpl @Inject constructor(
    private val dao: ReadingPositionDao,
) : ReadingPositionStore {

    override suspend fun save(itemId: String, cfi: String) {
        dao.upsert(ReadingPositionEntity(itemId = itemId, cfi = cfi, localUpdatedAt = System.currentTimeMillis()))
    }

    override suspend fun load(itemId: String): String? =
        dao.getByItemId(itemId)?.cfi

    override suspend fun loadLocalUpdatedAt(itemId: String): Long =
        dao.getByItemId(itemId)?.localUpdatedAt ?: 0L

    override suspend fun updateLocalTimestamp(itemId: String, millis: Long) {
        // Use upsert so that a row is always created — the UPDATE-only query silently does nothing
        // when no row exists yet, leaving localUpdatedAt = 0 and causing the server to win every
        // subsequent sync cycle until the user has moved enough to trigger a position save.
        val existing = dao.getByItemId(itemId)
        dao.upsert(ReadingPositionEntity(
            itemId = itemId,
            cfi = existing?.cfi ?: "",
            localUpdatedAt = millis,
        ))
    }
}
