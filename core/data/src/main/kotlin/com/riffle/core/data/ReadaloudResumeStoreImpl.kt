package com.riffle.core.data

import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadaloudResumePositionEntity
import com.riffle.core.common.Clock
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import javax.inject.Inject

class ReadaloudResumeStoreImpl @Inject constructor(
    private val dao: ReadaloudResumePositionDao,
    private val clock: Clock,
) : ReadaloudResumeStore {

    override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {
        dao.upsert(
            ReadaloudResumePositionEntity(
                sourceId = sourceId,
                itemId = itemId,
                href = position.href,
                progression = position.progression,
                fragmentRef = position.fragmentRef,
                localUpdatedAt = clock.nowMs(),
            )
        )
    }

    override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? =
        dao.getByItemId(sourceId, itemId)?.let {
            ReadaloudResumePosition(href = it.href, progression = it.progression, fragmentRef = it.fragmentRef)
        }

    override suspend fun clear(sourceId: String, itemId: String) = dao.deleteByItemId(sourceId, itemId)
}
