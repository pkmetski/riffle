package com.riffle.core.data.websource

import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity

class PositionTombstoneWriterImpl constructor(
    private val readingPositionDao: ReadingPositionDao,
    private val audiobookPositionDao: AudiobookPositionDao,
    private val clock: Clock,
) : PositionTombstoneWriter {
    override suspend fun markDeleted(sourceId: String, itemId: String) {
        val now = clock.nowMs()
        // markDeleted is an UPDATE and is a no-op when no row exists (item was added to the
        // library but never opened). Upsert a tombstone row in that case so a subsequent
        // WebDAV sync can push the deletion to other devices and the materializer's
        // deleted-filter keeps the item gone after the next sweep.
        readingPositionDao.markDeleted(sourceId, itemId, now)
        if (readingPositionDao.getByItemId(sourceId, itemId) == null) {
            readingPositionDao.upsert(ReadingPositionEntity(sourceId, itemId, "", now, 0L, deleted = true))
        }
        audiobookPositionDao.markDeleted(sourceId, itemId, now)
        if (audiobookPositionDao.getByItemId(sourceId, itemId) == null) {
            audiobookPositionDao.upsert(AudiobookPositionEntity(sourceId, itemId, 0.0, now, 0L, deleted = true))
        }
    }
}
