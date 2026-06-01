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
        val addedAt: Long? = null,
    )

    @Serializable
    data class AbsMediaDto(
        val metadata: AbsMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsEbookFileDto? = null,
    )

    @Serializable
    data class AbsEbookFileDto(
        val ino: String = "",
    )

    @Serializable
    data class AbsMetadataDto(
        val title: String = "",
        val authorName: String = "",
        val description: String? = null,
        @SerialName("seriesName") val seriesName: String? = null,
        val publishedYear: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
        val isbn: String? = null,
        val asin: String? = null,
    )

    @Serializable
    data class AbsProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}
