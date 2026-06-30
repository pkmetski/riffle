package com.riffle.app.feature.library

import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopReadaloudReviewRepository : ReadaloudReviewRepository {
    override fun observeReview(storytellerServerId: String, absServerId: String?): Flow<ReadaloudReview> =
        flowOf(ReadaloudReview(emptyList(), emptyList(), emptyList()))
    override suspend fun searchAbsItems(absServerId: String, query: String, filter: AbsFormatFilter): List<AbsPickerItem> = emptyList()
}
