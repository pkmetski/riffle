package com.riffle.app.feature.library

import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudLinkRepository : ReadaloudLinkRepository {
    override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
    override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
    override fun observeLinkedStorytellerBookIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLink? = null
    override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun unlinkStorytellerBook(storytellerServerId: String, storytellerBookId: String) = Unit
    override suspend fun countForServer(serverId: String): Int = 0
}
