package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.ReadingPositionStore

// ReadingPositionStore backed by any ReadingPositionDao implementation.
// Applies a monotonic timestamp guard on save() so localUpdatedAt always
// advances beyond any previously-adopted server stamp (issue #528 guard).
// Used on iOS via IosReadingPositionDao; Android uses its own androidMain impl.
internal open class DaoBackedReadingPositionStore(
    private val dao: ReadingPositionDao,
    private val clock: Clock,
) : ReadingPositionStore {

    override suspend fun save(sourceId: String, itemId: String, payload: String) {
        val existing = dao.getByItemId(sourceId, itemId)
        if (existing?.cfi == payload) return // idempotent — no re-stamp
        val now = clock.nowMs()
        val stamp = if (existing != null) maxOf(now, existing.localUpdatedAt + 1) else now
        dao.upsert(
            ReadingPositionEntity(
                sourceId = sourceId,
                itemId = itemId,
                cfi = payload,
                localUpdatedAt = stamp,
                lastSyncedAt = existing?.lastSyncedAt ?: stamp,
                deleted = false,
            ),
        )
    }

    override suspend fun load(sourceId: String, itemId: String): String? =
        dao.getByItemId(sourceId, itemId)?.takeIf { !it.deleted }?.cfi

    override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long =
        dao.getByItemId(sourceId, itemId)?.localUpdatedAt ?: 0L

    override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long =
        dao.getByItemId(sourceId, itemId)?.lastSyncedAt ?: 0L

    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) =
        dao.updateLocalTimestamp(sourceId, itemId, millis)

    override suspend fun acceptServer(
        sourceId: String,
        itemId: String,
        payload: String,
        serverStamp: Long,
    ) {
        val existing = dao.getByItemId(sourceId, itemId)
        dao.acceptServerIfUnchanged(
            sourceId = sourceId,
            itemId = itemId,
            position = payload,
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
