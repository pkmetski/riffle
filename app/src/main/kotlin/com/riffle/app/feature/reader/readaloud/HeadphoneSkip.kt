package com.riffle.app.feature.reader.readaloud

import androidx.media3.common.C
import androidx.media3.common.Player
import com.riffle.feature.player.SkipIntervals

private const val MS_PER_SEC = 1000.0

/**
 * Maps a headphone next/prev player command to the seek target it should produce. Returns
 * `null` for any command that is not a headphone next/prev — the caller must let those pass
 * through to the default Media3 handling. Extracted as a top-level function so unit tests
 * can cover the routing without loading the Android-bound [AudioPlayerService] class.
 */
internal fun headphoneSkipTargetMs(
    playerCommand: Int,
    currentPositionMs: Long,
    durationMs: Long,
    skipIntervals: SkipIntervals,
): Long? = when (playerCommand) {
    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
        val durationSec = if (durationMs > 0L && durationMs != C.TIME_UNSET) durationMs / MS_PER_SEC else 0.0
        val target = skipIntervals.forwardTargetSec(currentPositionMs / MS_PER_SEC, durationSec)
        (target * MS_PER_SEC).toLong()
    }
    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
        val target = skipIntervals.backwardTargetSec(currentPositionMs / MS_PER_SEC)
        (target * MS_PER_SEC).toLong()
    }
    else -> null
}
