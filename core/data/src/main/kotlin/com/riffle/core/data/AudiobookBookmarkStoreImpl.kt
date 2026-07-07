package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.AudiobookBookmarkStore
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AudiobookBookmarkStoreImpl @Inject constructor(
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

    override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String {
        val id = UUID.randomUUID().toString()
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
