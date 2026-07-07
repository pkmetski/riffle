package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import javax.inject.Inject

/**
 * Enumerates the `(serverId, itemId)` pairs with at least one dirty annotation row
 * (`updatedAt > lastSyncedAt`) for [AnnotationSweep] to push (#321, ADR 0036). The same seam shape
 * as [DirtyProgressLedger] / [DirtyBookmarkLedger] — keeps the DAO query out of the sweep so it can
 * be faked without a Room dependency.
 */
fun interface DirtyAnnotationLedger {
    suspend fun dirtySourceItems(): List<AnnotationDao.DirtySourceItem>
}

/** [DirtyAnnotationLedger] backed by the annotation DAO's `dirtySourceItems` query. */
class RoomDirtyAnnotationLedger @Inject constructor(
    private val annotationDao: AnnotationDao,
) : DirtyAnnotationLedger {
    override suspend fun dirtySourceItems(): List<AnnotationDao.DirtySourceItem> =
        annotationDao.dirtySourceItems()
}
