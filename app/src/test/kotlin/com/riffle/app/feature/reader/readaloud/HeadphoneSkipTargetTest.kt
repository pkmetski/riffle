package com.riffle.app.feature.reader.readaloud

import androidx.media3.common.C
import androidx.media3.common.Player
import com.riffle.app.feature.reader.readaloud.headphoneSkipTargetMs
import com.riffle.feature.player.SkipIntervals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the headphone next/prev → skip-by-seconds routing introduced to fix the bug where
 * headphone next/prev navigated chapters instead of seeking forward/backward.
 *
 * Regression: if [headphoneSkipTargetMs] stops intercepting the seek-to-next/prev commands,
 * or computes the wrong target, these assertions flip.
 */
class HeadphoneSkipTargetTest {

    private val intervals = SkipIntervals(forwardSec = 30, backwardSec = 15)

    @Test
    fun seekToNextProducesForwardSkip() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(90_000L, targetMs)
    }

    @Test
    fun seekToNextMediaItemProducesForwardSkip() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(90_000L, targetMs)
    }

    @Test
    fun seekToPreviousProducesBackwardSkip() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_PREVIOUS,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(45_000L, targetMs)
    }

    @Test
    fun seekToPreviousMediaItemProducesBackwardSkip() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(45_000L, targetMs)
    }

    @Test
    fun forwardSkipClampsAtDuration() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            currentPositionMs = 290_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(300_000L, targetMs)
    }

    @Test
    fun backwardSkipClampsAtZero() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_PREVIOUS,
            currentPositionMs = 5_000L,
            durationMs = 300_000L,
            skipIntervals = intervals,
        )
        assertEquals(0L, targetMs)
    }

    @Test
    fun forwardSkipWithUnknownDurationDoesNotClamp() {
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            currentPositionMs = 60_000L,
            durationMs = C.TIME_UNSET,
            skipIntervals = intervals,
        )
        assertEquals(90_000L, targetMs)
    }

    @Test
    fun forwardSkipWithZeroDurationDoesNotClamp() {
        // durationMs == 0L passes the TIME_UNSET guard but must be treated as unknown too —
        // otherwise forwardTargetSec receives durationSec = 0.0 and its coerceAtMost guard
        // (durationSec > 0.0) is skipped, letting the seek overshoot on unloaded items.
        val targetMs = headphoneSkipTargetMs(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            currentPositionMs = 60_000L,
            durationMs = 0L,
            skipIntervals = intervals,
        )
        assertEquals(90_000L, targetMs)
    }

    @Test
    fun otherCommandsReturnNull() {
        assertNull(
            headphoneSkipTargetMs(
                playerCommand = Player.COMMAND_PLAY_PAUSE,
                currentPositionMs = 60_000L,
                durationMs = 300_000L,
                skipIntervals = intervals,
            ),
        )
        assertNull(
            headphoneSkipTargetMs(
                playerCommand = Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                currentPositionMs = 60_000L,
                durationMs = 300_000L,
                skipIntervals = intervals,
            ),
        )
    }
}
