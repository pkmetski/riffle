package com.riffle.core.network

import kotlinx.serialization.Serializable

@Serializable
data class NetworkAbsAuthorUpdate(val name: String)

@Serializable
data class NetworkAbsSeriesUpdate(
    val name: String,
    val sequence: String? = null,
)

@Serializable
data class NetworkAbsMetadataUpdate(
    val title: String? = null,
    val authors: List<NetworkAbsAuthorUpdate> = emptyList(),
    val series: List<NetworkAbsSeriesUpdate> = emptyList(),
    val genres: List<String> = emptyList(),
    val publishedYear: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val language: String? = null,
)

@Serializable
data class NetworkAbsMediaUpdate(val metadata: NetworkAbsMetadataUpdate)

@Serializable
data class NetworkAbsChapterUpdate(
    val id: Int,
    val start: Double,
    val end: Double,
    val title: String,
)

@Serializable
data class NetworkAbsChaptersUpdate(val chapters: List<NetworkAbsChapterUpdate>)

@Serializable
data class NetworkAbsCoverUrlUpdate(val url: String)
