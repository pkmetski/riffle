package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsProgressResponse(
    val ebookLocation: String = "",
    val ebookProgress: Float = 0f,
    val lastUpdate: Long = 0L,
)
