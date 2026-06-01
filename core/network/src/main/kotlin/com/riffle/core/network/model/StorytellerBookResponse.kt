package com.riffle.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StorytellerBookResponse(
    val id: Long,
    val title: String,
    val authors: List<StorytellerAuthorResponse> = emptyList(),
    @SerialName("processing_status") val processingStatus: StorytellerProcessingStatusResponse? = null,
    // Identifiers — surfaced when Storyteller's metadata exposes them; nullable so
    // the matcher's Tier 1 simply falls through to Tier 2 when absent.
    val isbn: String? = null,
    val asin: String? = null,
)

@Serializable
data class StorytellerAuthorResponse(
    val name: String,
    @SerialName("file_as") val fileAs: String? = null,
    val role: String? = null,
)

@Serializable
data class StorytellerProcessingStatusResponse(
    @SerialName("current_task") val currentTask: String? = null,
    @SerialName("is_processing") val isProcessing: Boolean? = null,
    @SerialName("is_queued") val isQueued: Boolean? = null,
)
