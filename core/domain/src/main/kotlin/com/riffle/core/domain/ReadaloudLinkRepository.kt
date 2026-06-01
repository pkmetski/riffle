package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ReadaloudLinkRepository {
    fun observeAll(): Flow<List<ReadaloudLink>>
    fun observeLinkedAbsItemIds(): Flow<Set<String>>
    fun observeLinkedStorytellerBookIds(): Flow<Set<String>>
    suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): ReadaloudLink?
    suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLink?
    suspend fun unlink(storytellerServerId: String, storytellerBookId: String)
    suspend fun countForServer(serverId: String): Int
}
