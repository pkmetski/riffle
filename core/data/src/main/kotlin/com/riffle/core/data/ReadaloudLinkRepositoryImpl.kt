package com.riffle.core.data

import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.AudiobookIdentityResult
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

    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? =
        dao.findByAbsItem(absSourceId, absLibraryItemId)?.toDomain()

    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> =
        dao.findByStorytellerBook(storytellerSourceId, storytellerBookId).map { it.toDomain() }

    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) =
        dao.deleteByAbsItem(absSourceId, absLibraryItemId)

    override suspend fun countForSource(sourceId: String): Int =
        dao.countForSource(sourceId)

    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) =
        dao.updateIdentityResult(absSourceId, absLibraryItemId, result.name)

    private fun ReadaloudLinkEntity.toDomain() = ReadaloudLink(
        storytellerSourceId = storytellerSourceId,
        storytellerBookId = storytellerBookId,
        absSourceId = absSourceId,
        absLibraryItemId = absLibraryItemId,
        userConfirmed = userConfirmed,
        identityResult = runCatching { AudiobookIdentityResult.valueOf(identityResult) }
            .getOrDefault(AudiobookIdentityResult.UNKNOWN),
    )
}
