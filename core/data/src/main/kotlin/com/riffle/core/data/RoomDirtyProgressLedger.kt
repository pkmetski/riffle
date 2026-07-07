package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.ReadingPositionDao
import javax.inject.Inject

/** [DirtyProgressLedger] backed by the two position DAOs' `localUpdatedAt > lastSyncedAt` queries. */
class RoomDirtyProgressLedger @Inject constructor(
    private val readingDao: ReadingPositionDao,
    private val audiobookDao: AudiobookPositionDao,
) : DirtyProgressLedger {

    override suspend fun serversWithDirty(): List<String> =
        (readingDao.sourcesWithDirtyRows() + audiobookDao.sourcesWithDirtyRows()).distinct()

    override suspend fun dirtyEbookItems(serverId: String): List<String> =
        readingDao.dirtyForSource(serverId).map { it.itemId }

    override suspend fun dirtyAudioItems(serverId: String): List<String> =
        audiobookDao.dirtyForSource(serverId).map { it.itemId }
}
