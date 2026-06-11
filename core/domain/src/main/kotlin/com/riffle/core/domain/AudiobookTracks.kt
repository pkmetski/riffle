package com.riffle.core.domain

/** One audio file of an [Audiobook] on the book's continuous timeline (seconds), ordered by offset. */
data class AudiobookTrackSpan(
    val index: Int,
    val startOffsetSec: Double,
    val durationSec: Double,
)

/**
 * Maps between the audiobook's absolute book-timeline position (what [Progress Sync] and the UI use)
 * and the per-track (file, offset-within-file) position the player seeks to. ABS serves an audiobook
 * as several concatenated tracks; ExoPlayer plays them as a playlist, so a book-absolute seek must be
 * resolved to a (track index, offset) pair. Pure and unit-tested (ADR 0029).
 */
object AudiobookTracks {

    /** The index of the track containing [absoluteSec] (clamped to the track range). */
    fun trackIndexAt(absoluteSec: Double, tracks: List<AudiobookTrackSpan>): Int {
        if (tracks.isEmpty()) return 0
        val i = tracks.indexOfLast { absoluteSec >= it.startOffsetSec }
        return if (i < 0) 0 else i
    }

    /** The offset within its track for the book-absolute [absoluteSec] (never negative). */
    fun offsetInTrackSec(absoluteSec: Double, tracks: List<AudiobookTrackSpan>): Double {
        if (tracks.isEmpty()) return absoluteSec.coerceAtLeast(0.0)
        val track = tracks[trackIndexAt(absoluteSec, tracks)]
        return (absoluteSec - track.startOffsetSec).coerceAtLeast(0.0)
    }

    /** The book-absolute position for a player position of [offsetInTrackSec] within track [index]. */
    fun absoluteSec(index: Int, offsetInTrackSec: Double, tracks: List<AudiobookTrackSpan>): Double {
        val track = tracks.getOrNull(index) ?: return offsetInTrackSec.coerceAtLeast(0.0)
        return track.startOffsetSec + offsetInTrackSec.coerceAtLeast(0.0)
    }

    /**
     * Resolves a book-absolute resume position to the (track index, in-track offset) start point to
     * seed into the player's *initial* media-item list, so playback buffers from the resume point
     * rather than starting at track 0 / offset 0 and audibly snapping forward (ADR 0029). [absoluteSec]
     * is clamped into `[0, durationSec]` (or `[0, absoluteSec]` when the duration is unknown).
     */
    fun startPositionFor(
        absoluteSec: Double,
        durationSec: Double,
        tracks: List<AudiobookTrackSpan>,
    ): StartPosition {
        val clamped = absoluteSec.coerceIn(0.0, if (durationSec > 0) durationSec else absoluteSec)
        return StartPosition(trackIndexAt(clamped, tracks), (offsetInTrackSec(clamped, tracks) * 1000).toLong())
    }
}

/** A resolved player start point: which track and the offset within it (ms), for `setMediaItems`. */
data class StartPosition(val trackIndex: Int, val offsetMs: Long)
