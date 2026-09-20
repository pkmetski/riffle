package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.sync.DirtyBookmarkLedger

/**
 * [DirtyBookmarkLedger] backed by [AudiobookBookmarkDao]'s dirty-row queries.
 *
 * Android built this inline as an anonymous object inside its Koin module; iOS needs the identical
 * ledger for its own [com.riffle.core.sync.ProgressSweep] (#1071 §14), so it is a named class in
 * `commonMain` that both hosts bind rather than two anonymous copies that can drift.
 */
class DaoDirtyBookmarkLedger(
    private val dao: AudiobookBookmarkDao,
) : DirtyBookmarkLedger {

    override suspend fun serversWithDirty(): List<String> = dao.sourcesWithDirtyRows()

    override suspend fun dirtyItems(sourceId: String): List<String> =
        dao.dirtyForSource(sourceId).map { it.itemId }.distinct()
}
