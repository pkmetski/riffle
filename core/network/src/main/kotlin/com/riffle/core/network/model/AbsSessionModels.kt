package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsEbookProgressRequest(
    val ebookLocation: String,
    val ebookProgress: Float,
)
