package com.riffle.core.data

import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadaloudLinkRepositoryImpl @Inject constructor(
    private val dao: ReadaloudLinkDao,
) : ReadaloudLinkRepository {

    override fun observeAll(): Flow<List<ReadaloudLink>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeLinkedAbsItemIds(): Flow<Set<String>> =
        dao.observeLinkedAbsItemIds().map { it.toSet() }

    override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLink? =
        dao.findByAbsItem(absServerId, absLibraryItemId)?.toDomain()

    override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLink> =
        dao.findByStorytellerBook(storytellerServerId, storytellerBookId).map { it.toDomain() }

    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) =
        dao.deleteByAbsItem(absServerId, absLibraryItemId)

    override suspend fun countForServer(serverId: String): Int =
        dao.countForServer(serverId)

    private fun ReadaloudLinkEntity.toDomain() = ReadaloudLink(
        storytellerServerId = storytellerServerId,
        storytellerBookId = storytellerBookId,
        absServerId = absServerId,
        absLibraryItemId = absLibraryItemId,
        userConfirmed = userConfirmed,
    )
}
