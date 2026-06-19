package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsSeriesResponse(val results: List<AbsSeriesDto>) {

    @Serializable
    data class AbsSeriesDto(
        val id: String,
        val name: String,
        val libraryId: String = "",
        val books: List<AbsSeriesBookDto> = emptyList(),
    )

    @Serializable
    data class AbsSeriesBookDto(
        val id: String,
        val libraryId: String,
        val seriesSequence: String? = null,
        val media: AbsSeriesMediaDto,
        val userMediaProgress: AbsSeriesProgressDto? = null,
        val updatedAt: Long? = null,
    )

    @Serializable
    data class AbsSeriesMediaDto(
        val metadata: AbsSeriesMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsSeriesEbookFileDto? = null,
    )

    @Serializable
    data class AbsSeriesEbookFileDto(
        val ino: String = "",
    )

    @Serializable
    data class AbsSeriesMetadataDto(
        val title: String = "",
        val authorName: String = "",
        val description: String? = null,
        val seriesName: String? = null,
        val publishedYear: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
    )

    @Serializable
    data class AbsSeriesProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}
