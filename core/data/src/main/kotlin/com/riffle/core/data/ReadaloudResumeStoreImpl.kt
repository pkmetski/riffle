package com.riffle.core.data

import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadaloudResumePositionEntity
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import javax.inject.Inject

class ReadaloudResumeStoreImpl @Inject constructor(
    private val dao: ReadaloudResumePositionDao,
) : ReadaloudResumeStore {

    override suspend fun save(serverId: String, itemId: String, position: ReadaloudResumePosition) {
        dao.upsert(
            ReadaloudResumePositionEntity(
                serverId = serverId,
                itemId = itemId,
                href = position.href,
                progression = position.progression,
                fragmentRef = position.fragmentRef,
                localUpdatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun load(serverId: String, itemId: String): ReadaloudResumePosition? =
        dao.getByItemId(serverId, itemId)?.let {
            ReadaloudResumePosition(href = it.href, progression = it.progression, fragmentRef = it.fragmentRef)
        }
}
