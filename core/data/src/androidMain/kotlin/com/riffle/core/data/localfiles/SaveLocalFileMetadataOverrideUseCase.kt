package com.riffle.core.data.localfiles

import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity

class SaveLocalFileMetadataOverrideUseCase constructor(
    private val overrideDao: LocalFileMetadataOverrideDao,
) {
    suspend operator fun invoke(
        sourceId: String,
        sourceItemId: String,
        title: String?,
        author: String?,
        seriesName: String?,
        seriesIndex: Double?,
        coverUrl: String? = null,
    ) {
        overrideDao.upsert(
            LocalFileMetadataOverrideEntity(
                sourceId = sourceId,
                sourceItemId = sourceItemId,
                title = title?.ifBlank { null },
                author = author?.ifBlank { null },
                seriesName = seriesName?.ifBlank { null },
                seriesIndex = seriesIndex,
                coverUrl = coverUrl,
            ),
        )
    }
}
