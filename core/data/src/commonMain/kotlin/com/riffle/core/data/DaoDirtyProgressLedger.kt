package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.sync.DirtyProgressLedger

/**
 * [DirtyProgressLedger] backed by the two position DAOs' `localUpdatedAt > lastSyncedAt` queries.
 *
 * Lives in `commonMain` (it was `RoomDirtyProgressLedger` in `androidMain` until #1071 §14) because
 * it only ever touched the DAO *interfaces*, which both Room and iOS's SQLDelight DAOs implement.
 * iOS's [com.riffle.core.sync.ProgressSweep] binding needs exactly the same ledger, and a second
 * copy in `iosMain` would be the divergent-private-copy anti-pattern AGENTS.md names.
 */
class DaoDirtyProgressLedger(
    private val readingDao: ReadingPositionDao,
    private val audiobookDao: AudiobookPositionDao,
) : DirtyProgressLedger {

    override suspend fun serversWithDirty(): List<String> =
        (readingDao.sourcesWithDirtyRows() + audiobookDao.sourcesWithDirtyRows()).distinct()

    override suspend fun dirtyEbookItems(sourceId: String): List<String> =
        readingDao.dirtyForSource(sourceId).map { it.itemId }

    override suspend fun dirtyAudioItems(sourceId: String): List<String> =
        audiobookDao.dirtyForSource(sourceId).map { it.itemId }
}
