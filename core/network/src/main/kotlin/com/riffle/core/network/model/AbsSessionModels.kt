package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsEbookProgressRequest(
    val ebookLocation: String,
    val ebookProgress: Float,
)

@Serializable
internal data class AbsAudiobookProgressRequest(
    val currentTime: Double,
    val duration: Double,
    // ABS stores the fraction as sent rather than recomputing it; without it the audiobook shows 0%.
    val progress: Double,
)
