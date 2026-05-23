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
        dao.updateLocalTimestamp(itemId, millis)
    }
}
