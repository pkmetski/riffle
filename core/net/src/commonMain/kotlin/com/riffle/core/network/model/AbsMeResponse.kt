package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsMeResponse(
    val id: String = "",
    val mediaProgress: List<AbsMediaProgressDto> = emptyList(),
) {
    @Serializable
    data class AbsMediaProgressDto(
        val libraryItemId: String = "",
        val ebookProgress: Float? = null,
        val progress: Float = 0f,
        val currentTime: Double = 0.0,
        val duration: Double = 0.0,
        val isFinished: Boolean = false,
        val lastUpdate: Long? = null,
        val finishedAt: Long? = null,
    )
}
