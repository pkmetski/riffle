package com.riffle.app.feature.library

import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudReviewRepository : ReadaloudReviewRepository {
    override fun observeReview(storytellerServerId: String): Flow<ReadaloudReview> =
        flowOf(ReadaloudReview(emptyList(), emptyList(), emptyList()))
    override suspend fun confirmCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun dismissCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun dismissBook(storytellerServerId: String, storytellerBookId: String) = Unit
    override suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String) = Unit
    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun pairManually(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) = Unit
    override suspend fun searchAbsItems(query: String, filter: AbsFormatFilter): List<AbsPickerItem> = emptyList()
}
