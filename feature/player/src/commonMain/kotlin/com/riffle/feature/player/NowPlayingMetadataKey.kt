package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter

/**
 * Identity of the "what the lock screen / system notification currently says" line, so both
 * platforms can refresh that metadata at exactly the same cadence: at most once per whole minute
 * of remaining time, plus immediately on every chapter transition.
 *
 * Shared because the refresh decision is pure position math, and a private per-platform copy of it
 * is how iOS ended up pushing [AudioPlayerInterface]-level metadata exactly once per session and
 * never again (the lock screen kept showing chapter 1 for the whole book).
 *
 * Android: `AudiobookController.maybeUpdateRemainingMetadata`.
 * iOS: `IosAudioPlayerController.maybeRefreshNowPlaying`.
 */
data class NowPlayingMetadataKey(
    val remainingMinuteBucket: Long,
    val chapterIndex: Int,
) {
    companion object {
        /** The key no real position can produce — the "nothing pushed yet" sentinel. */
        val NONE = NowPlayingMetadataKey(remainingMinuteBucket = -1L, chapterIndex = -1)

        /** Remaining book time at [positionSec], never negative. */
        fun remainingSec(positionSec: Double, durationSec: Double): Double =
            (durationSec - positionSec).coerceAtLeast(0.0)

        fun of(positionSec: Double, durationSec: Double, chapter: AudiobookChapter?): NowPlayingMetadataKey =
            NowPlayingMetadataKey(
                remainingMinuteBucket = (remainingSec(positionSec, durationSec) / 60.0).toLong(),
                chapterIndex = chapter?.index ?: -1,
            )
    }
}
