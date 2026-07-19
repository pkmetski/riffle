package com.riffle.app.feature.library

import com.riffle.core.models.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudLinkRepository : ReadaloudLinkRepository {
    override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
    override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
    override suspend fun countForSource(sourceId: String): Int = 0
}
