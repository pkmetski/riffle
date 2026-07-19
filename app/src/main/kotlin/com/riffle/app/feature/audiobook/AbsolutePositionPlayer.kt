package com.riffle.app.feature.audiobook

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.riffle.core.models.AudiobookTracks

/**
 * Wraps [ExoPlayer] and overrides the three position-related methods so that Android's OS media
 * controls (notification, lock screen, Bluetooth) see book-absolute progress rather than
 * per-track (per-chapter) progress.
 *
 * When no audiobook is active ([SharedAudiobookContext.spans] is empty) every call passes through
 * to the delegate unchanged, so Readaloud playback is unaffected.
 *
 * The four overrides are mutually consistent:
 * - [getCurrentPosition]  → book-absolute milliseconds
 * - [getBufferedPosition] → book-absolute milliseconds (buffered-ahead frontier)
 * - [getDuration]         → total book duration in milliseconds
 * - [seekTo] (single-arg) → resolves absolute ms → (track index, in-track offset) and delegates
 *
 * The two-argument [seekTo](mediaItemIndex, positionMs) is intentionally NOT overridden: the
 * [AudiobookController] already resolves absolute positions to (index, offset) before calling it
 * via the [MediaController] Binder, so it must reach [ExoPlayer] unchanged.
 */
@OptIn(UnstableApi::class)
class AbsolutePositionPlayer(private val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {

    override fun getCurrentPosition(): Long {
        val spans = SharedAudiobookContext.spans
        if (spans.isEmpty()) return exoPlayer.currentPosition
        return (AudiobookTracks.absoluteSec(
            exoPlayer.currentMediaItemIndex,
            exoPlayer.currentPosition / 1000.0,
            spans,
        ) * 1000.0).toLong()
    }

    override fun getBufferedPosition(): Long {
        val spans = SharedAudiobookContext.spans
        if (spans.isEmpty()) return exoPlayer.bufferedPosition
        return (AudiobookTracks.absoluteSec(
            exoPlayer.currentMediaItemIndex,
            exoPlayer.bufferedPosition / 1000.0,
            spans,
        ) * 1000.0).toLong()
    }

    override fun getDuration(): Long {
        val totalMs = SharedAudiobookContext.totalDurationMs
        return if (totalMs > 0L) totalMs else exoPlayer.duration
    }

    override fun seekTo(positionMs: Long) {
        val spans = SharedAudiobookContext.spans
        if (spans.isEmpty()) {
            exoPlayer.seekTo(positionMs)
            return
        }
        val absoluteSec = positionMs / 1000.0
        val trackIndex = AudiobookTracks.trackIndexAt(absoluteSec, spans)
        val offsetMs = (AudiobookTracks.offsetInTrackSec(absoluteSec, spans) * 1000.0).toLong()
        exoPlayer.seekTo(trackIndex, offsetMs)
    }
}
