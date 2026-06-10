package com.riffle.core.data

import com.riffle.core.domain.SegmentPlacement
import com.riffle.core.network.AbsAudioUrl
import com.riffle.core.network.NetworkAbsAudioTrack

/**
 * One queued media item for streaming playback (ADR 0028). [audioSrc] stays the Storyteller segment
 * path so it remains the media id — the existing clip/highlight/skip machinery is reused unchanged;
 * only [url] (an ABS track) and the clip window differ from the bundle path.
 */
data class StreamingMediaItem(
    val audioSrc: String,
    val url: String,
    val clipStartMs: Long,
    val clipEndMs: Long,
)

/**
 * Builds the streaming playlist: one item per Storyteller segment, pointed at its ABS track and
 * clipped to the segment's window so the player's timeline matches the SMIL exactly (keeping the
 * highlight precise across the 1:1, dropped-intro, and single-file cases).
 */
object StreamingMediaPlan {
    fun build(
        placements: List<SegmentPlacement>,
        tracks: List<NetworkAbsAudioTrack>,
        baseUrl: String,
        itemId: String,
    ): List<StreamingMediaItem> = placements.map { p ->
        val track = tracks[p.absTrackIndex]
        StreamingMediaItem(
            audioSrc = p.audioSrc,
            url = AbsAudioUrl.track(baseUrl, itemId, track.ino),
            clipStartMs = (p.startSec * 1000).toLong(),
            clipEndMs = ((p.startSec + p.durationSec) * 1000).toLong(),
        )
    }
}
