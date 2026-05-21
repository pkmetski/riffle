package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsItemResponse(
    val id: String,
    val libraryId: String = "",
    val media: AbsItemMediaDto,
) {
    @Serializable
    data class AbsItemMediaDto(
        val ebookFile: AbsItemEbookFileDto? = null,
    )

    @Serializable
    data class AbsItemEbookFileDto(
        val ino: String = "",
    )
}
