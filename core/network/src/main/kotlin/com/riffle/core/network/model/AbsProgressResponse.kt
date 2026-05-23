package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsProgressResponse(
    val ebookLocation: String = "",
    val lastUpdate: Long = 0L,
)
