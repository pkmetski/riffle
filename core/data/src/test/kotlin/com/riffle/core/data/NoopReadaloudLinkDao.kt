package com.riffle.core.data

import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudLinkDao : ReadaloudLinkDao {
    override suspend fun upsert(entity: ReadaloudLinkEntity) = Unit
    override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): ReadaloudLinkEntity? = null
    override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLinkEntity? = null
    override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(emptyList())
    override suspend fun countForServer(serverId: String): Int = 0
    override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) = Unit
}
