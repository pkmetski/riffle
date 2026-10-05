package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.domain.AudiobookBookmarkStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AudiobookBookmarkStoreImpl constructor(
    private val dao: AudiobookBookmarkDao,
) : AudiobookBookmarkStore {

    override fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>> =
        dao.observeForItem(sourceId, itemId).map { rows -> rows.map { it.toDomain() } }

    override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>> =
        dao.observeForSource(sourceId).map { rows -> rows.map { it.toDomain() } }

    private fun AudiobookBookmarkEntity.toDomain() =
        AudiobookBookmark(id, sourceId, itemId, positionSec, title, createdAt)

    override fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean> =
        dao.observeDirtyCountForItem(sourceId, itemId).map { it > 0 }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String {
        val id = Uuid.random().toString()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = id, sourceId = sourceId, itemId = itemId, positionSec = positionSec,
                title = title, createdAt = now, localUpdatedAt = now, lastSyncedAt = 0, deleted = false,
            ),
        )
        return id
    }

    override suspend fun rename(id: String, title: String, now: Long) {
        val e = dao.getById(id) ?: return
        dao.upsert(e.copy(title = title, localUpdatedAt = now))
    }

    override suspend fun delete(id: String, now: Long) {
        val e = dao.getById(id) ?: return
        dao.upsert(e.copy(deleted = true, localUpdatedAt = now))
    }
}
