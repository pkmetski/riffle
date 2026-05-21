package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsCollectionsResponse(val results: List<AbsCollectionDto>) {

    @Serializable
    data class AbsCollectionDto(
        val id: String,
        val name: String,
        val libraryId: String,
        val books: List<AbsCollectionBookDto> = emptyList(),
    )

    @Serializable
    data class AbsCollectionBookDto(
        val id: String,
        val libraryId: String,
        val media: AbsCollectionMediaDto,
        val userMediaProgress: AbsCollectionProgressDto? = null,
    )

    @Serializable
    data class AbsCollectionMediaDto(
        val metadata: AbsCollectionMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsCollectionEbookFileDto? = null,
    )

    @Serializable
    data class AbsCollectionEbookFileDto(
        val ino: String = "",
    )

    @Serializable
    data class AbsCollectionMetadataDto(
        val title: String = "",
        val authorName: String = "",
    )

    @Serializable
    data class AbsCollectionProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}
