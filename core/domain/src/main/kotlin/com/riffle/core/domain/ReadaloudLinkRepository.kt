package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ReadaloudLinkRepository {
    fun observeAll(): Flow<List<ReadaloudLink>>
    fun observeLinkedAbsItemIds(): Flow<Set<String>>

    /** Unique by ABS PK. */
    suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLink?

    /** A readaloud can link to many ABS items (ebook + audiobook stub in different libraries). */
    suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLink>

    /** Unlink one specific ABS row. */
    suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String)

    suspend fun countForServer(serverId: String): Int
}
