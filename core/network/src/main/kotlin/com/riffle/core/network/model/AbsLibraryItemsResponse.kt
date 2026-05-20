package com.riffle.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLibraryItemsResponse(val results: List<AbsLibraryItemDto>) {

    @Serializable
    data class AbsLibraryItemDto(
        val id: String,
        val libraryId: String,
        val media: AbsMediaDto,
        val userMediaProgress: AbsProgressDto? = null,
    )

    @Serializable
    data class AbsMediaDto(
        val metadata: AbsMetadataDto,
        val ebookFormat: String? = null,
    )

    @Serializable
    data class AbsMetadataDto(
        val title: String = "",
        val authorName: String = "",
    )

    @Serializable
    data class AbsProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}
