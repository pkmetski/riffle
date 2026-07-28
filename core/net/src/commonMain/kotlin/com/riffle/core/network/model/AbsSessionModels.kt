package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsEbookProgressRequest(
    val ebookLocation: String,
    val ebookProgress: Float,
    // Omitted from the JSON when null (encodeDefaults=false), so an ordinary position save sends
    // only the ebook fields and never disturbs the item's finished/audio state.
    val isFinished: Boolean? = null,
)

@Serializable
internal data class AbsAudiobookProgressRequest(
    val currentTime: Double,
    val duration: Double,
    // ABS stores the fraction as sent rather than recomputing it; without it the audiobook shows 0%.
    val progress: Double,
)
