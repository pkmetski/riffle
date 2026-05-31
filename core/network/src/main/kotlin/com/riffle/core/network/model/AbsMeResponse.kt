package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsMeResponse(
    val mediaProgress: List<AbsMediaProgressDto> = emptyList(),
) {
    @Serializable
    data class AbsMediaProgressDto(
        val libraryItemId: String = "",
        val ebookProgress: Float? = null,
        val progress: Float = 0f,
        val lastUpdate: Long? = null,
    )
}
