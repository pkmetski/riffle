package com.riffle.core.network.model

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/items/{id}/play`. Riffle direct-plays the ABS audio tracks through
 * Media3/ExoPlayer (ADR 0029); the supported MIME types let ABS decide direct-play vs transcode.
 */
@Serializable
internal data class AbsPlayRequest(
    val deviceInfo: AbsPlayDeviceInfo,
    val supportedMimeTypes: List<String>,
    val mediaPlayer: String = "exo-player",
    val forceDirectPlay: Boolean = true,
    val forceTranscode: Boolean = false,
)

@Serializable
internal data class AbsPlayDeviceInfo(
    val clientName: String = "Riffle",
    val deviceId: String,
)

/**
 * Response from `POST /api/items/{id}/play`. The `audioTracks` are the book's audio files placed on
 * one continuous timeline via `startOffset`; `chapters` are the ABS chapter markers (may be empty).
 */
@Serializable
internal data class AbsPlaySessionResponse(
    val id: String? = null,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val audioTracks: List<AbsPlayAudioTrack> = emptyList(),
    val chapters: List<AbsPlayChapter> = emptyList(),
)

@Serializable
internal data class AbsPlayAudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val contentUrl: String = "",
    val mimeType: String = "",
)

@Serializable
internal data class AbsPlayChapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)
