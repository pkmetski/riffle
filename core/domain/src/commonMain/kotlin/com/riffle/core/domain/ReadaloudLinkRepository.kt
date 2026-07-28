package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.ReadaloudLink

interface ReadaloudLinkRepository {
    fun observeAll(): Flow<List<ReadaloudLink>>
    fun observeLinkedAbsItemIds(): Flow<Set<String>>

    /** Unique by ABS PK. */
    suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink?

    /** A readaloud can link to many ABS items (ebook + audiobook stub in different libraries). */
    suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink>

    /** Unlink one specific ABS row. */
    suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String)

    suspend fun countForSource(sourceId: String): Int

    /** Persist the streaming identity verdict for an ABS item (ADR 0028). */
    suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
}
