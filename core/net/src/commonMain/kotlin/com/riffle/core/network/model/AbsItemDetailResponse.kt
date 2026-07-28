package com.riffle.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AbsItemDetailResponse(
    val id: String,
    val media: AbsItemDetailMediaDto,
)

@Serializable
data class AbsItemDetailMediaDto(
    val chapters: List<AbsItemChapterDto> = emptyList(),
)

@Serializable
data class AbsItemChapterDto(
    val id: Int = 0,
    @SerialName("start") val startSec: Double = 0.0,
    @SerialName("end") val endSec: Double = 0.0,
    val title: String = "",
)
