package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsProgressResponse(
    val ebookLocation: String = "",
    val ebookProgress: Float = 0f,
    // Audiobook progress shares this media-progress record: an offset in seconds into the
    // audio plus the audiobook's total duration (both 0 for ebook-only items).
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val lastUpdate: Long = 0L,
)
