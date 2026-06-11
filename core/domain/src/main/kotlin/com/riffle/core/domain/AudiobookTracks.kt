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
}
