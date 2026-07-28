package com.riffle.core.network

/** One audio file of an [Audiobook], placed on the book's continuous timeline via [startOffsetSec]. */
data class NetworkAudioTrack(
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
    /** Server-relative path to the audio bytes (e.g. `/api/items/<id>/file/<ino>`); needs the token. */
    val contentUrl: String,
    val mimeType: String,
)

/** An ABS chapter marker over the book's single logical timeline (seconds). */
data class NetworkAudioChapter(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

/**
 * A direct-play audiobook session opened from ABS: the ordered audio tracks, the chapter markers
 * (empty for a chapterless book), and the server-recorded position/duration (ADR 0029).
 */
data class NetworkPlaybackSession(
    val sessionId: String?,
    val tracks: List<NetworkAudioTrack>,
    val chapters: List<NetworkAudioChapter>,
    val currentTimeSec: Double,
    val durationSec: Double,
)

/** ABS audiobook playback: open a direct-play session for an item's audio (ADR 0029). */
interface AbsPlaybackApi {
    suspend fun openPlaybackSession(
        baseUrl: String,
        libraryItemId: String,
        deviceId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaybackSession>

    /** `POST /api/session/:sessionId/sync` — periodic position + listened-time update. */
    suspend fun syncPlaybackSession(
        baseUrl: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("syncPlaybackSession not implemented")

    /** `POST /api/session/:sessionId/close` — final position + listened-time; session ends. */
    suspend fun closePlaybackSession(
        baseUrl: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> = throw UnsupportedOperationException("closePlaybackSession not implemented")
}
