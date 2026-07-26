package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import javax.inject.Inject

class AudiobookBookmarkSyncStoreImpl @Inject constructor(
    private val dao: AudiobookBookmarkDao,
) : AudiobookBookmarkSyncStore {

    override suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String): List<SyncableAudiobookBookmark> =
        dao.allForItem(sourceId, itemId).map { it.toSyncable() }

    override suspend fun upsert(bookmark: SyncableAudiobookBookmark) =
        dao.upsert(bookmark.toEntity())

    override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean =
        dao.confirmPushedIfUnchanged(id, serverStamp, ifLocalUpdatedAt) > 0

    override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean =
        dao.hardDeleteIfUnchanged(id, ifLocalUpdatedAt) > 0

    override suspend fun hardDelete(id: String) = dao.hardDelete(id)

    private fun AudiobookBookmarkEntity.toSyncable() = SyncableAudiobookBookmark(
        id = id, sourceId = sourceId, itemId = itemId, positionSec = positionSec,
        title = title, createdAt = createdAt, localUpdatedAt = localUpdatedAt,
        lastSyncedAt = lastSyncedAt, deleted = deleted,
    )

    private fun SyncableAudiobookBookmark.toEntity() = AudiobookBookmarkEntity(
        id = id, sourceId = sourceId, itemId = itemId, positionSec = positionSec,
        title = title, createdAt = createdAt, localUpdatedAt = localUpdatedAt,
        lastSyncedAt = lastSyncedAt, deleted = deleted,
    )
}
