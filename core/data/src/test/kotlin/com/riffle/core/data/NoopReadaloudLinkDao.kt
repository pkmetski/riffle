package com.riffle.core.data

import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudLinkDao : ReadaloudLinkDao {
    override suspend fun upsert(entity: ReadaloudLinkEntity) = Unit
    override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLinkEntity? = null
    override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLinkEntity> = emptyList()
    override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(emptyList())
    override suspend fun allRows(): List<ReadaloudLinkEntity> = emptyList()
    override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun countForServer(serverId: String): Int = 0
    override suspend fun deleteByAbsItem(absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) = Unit
    override suspend fun updateIdentityResult(absServerId: String, absLibraryItemId: String, result: String) = Unit
}

internal object NoopReadaloudCandidateDao : ReadaloudCandidateDao {
    override suspend fun upsert(entity: ReadaloudCandidateEntity) = Unit
    override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) = Unit
    override suspend fun allRows(): List<ReadaloudCandidateEntity> = emptyList()
    override suspend fun clearAll() = Unit
    override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> = flowOf(emptyList())
    override fun observeForStorytellerServer(storytellerServerId: String): Flow<List<ReadaloudCandidateEntity>> = flowOf(emptyList())
    override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) = Unit
    override suspend fun deleteCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
}

internal object NoopReadaloudDismissalDao : ReadaloudDismissalDao {
    override suspend fun upsert(entity: ReadaloudDismissalEntity) = Unit
    override suspend fun allRows(): List<ReadaloudDismissalEntity> = emptyList()
    override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = flowOf(emptyList())
    override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudDismissalEntity> = emptyList()
    override suspend fun isBookDismissed(storytellerServerId: String, storytellerBookId: String): Boolean = false
    override suspend fun clearBookDismissal(storytellerServerId: String, storytellerBookId: String) = Unit
}
