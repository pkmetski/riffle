package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.sync.DirtyAnnotationLedger

/** [DirtyAnnotationLedger] backed by the annotation DAO's `dirtySourceItems` query. */
class RoomDirtyAnnotationLedger constructor(
    private val annotationDao: AnnotationDao,
) : DirtyAnnotationLedger {
    override suspend fun dirtySourceItems(): List<DirtyAnnotationLedger.DirtySourceItem> =
        annotationDao.dirtySourceItems().map { DirtyAnnotationLedger.DirtySourceItem(it.sourceId, it.itemId) }
}
