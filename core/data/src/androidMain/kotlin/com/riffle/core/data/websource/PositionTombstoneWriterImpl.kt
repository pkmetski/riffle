package com.riffle.core.data.websource

import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.ReadingPositionDao

class PositionTombstoneWriterImpl constructor(
    private val readingPositionDao: ReadingPositionDao,
    private val audiobookPositionDao: AudiobookPositionDao,
    private val clock: Clock,
) : PositionTombstoneWriter {
    override suspend fun markDeleted(sourceId: String, itemId: String) {
        val now = clock.nowMs()
        readingPositionDao.markDeleted(sourceId, itemId, now)
        audiobookPositionDao.markDeleted(sourceId, itemId, now)
    }
}
