package com.riffle.feature.library

interface LocalFileMetadataOverrideSaver {
    suspend operator fun invoke(
        sourceId: String,
        sourceItemId: String,
        title: String?,
        author: String?,
        seriesName: String?,
        seriesIndex: Double?,
        coverUrl: String? = null,
    )
}
