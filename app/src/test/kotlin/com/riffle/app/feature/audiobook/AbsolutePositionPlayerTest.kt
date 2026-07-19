package com.riffle.app.feature.audiobook

import androidx.media3.exoplayer.ExoPlayer
import com.riffle.core.models.AudiobookTrackSpan
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Buffer projection: [AbsolutePositionPlayer.getBufferedPosition] must add the current track's
 * `startOffsetSec` when [SharedAudiobookContext] has spans, and pass through otherwise. The parallel
 * of the well-known [getCurrentPosition] double-count trap — a plain forwarding delegate would
 * return per-track ms and the seek-bar buffered band would appear to sit before the playhead.
 */
class AbsolutePositionPlayerTest {

    @After
    fun tearDown() {
        SharedAudiobookContext.spans = emptyList()
        SharedAudiobookContext.totalDurationMs = 0L
    }

    @Test
    fun `getBufferedPosition adds current track startOffset when spans are set`() {
        SharedAudiobookContext.spans = listOf(
            AudiobookTrackSpan(index = 0, startOffsetSec = 0.0, durationSec = 600.0),
            AudiobookTrackSpan(index = 1, startOffsetSec = 600.0, durationSec = 600.0),
        )
        val exo = mockk<ExoPlayer>(relaxed = true) {
            every { currentMediaItemIndex } returns 1
            every { bufferedPosition } returns 30_000L
        }

        val projected = AbsolutePositionPlayer(exo).bufferedPosition

        // 600s (track 1 startOffset) + 30s (in-track buffered) = 630_000 ms.
        assertEquals(630_000L, projected)
    }

    @Test
    fun `getBufferedPosition passes through when no audiobook is active`() {
        // Readaloud path: spans empty → return ExoPlayer's raw per-track value untouched.
        SharedAudiobookContext.spans = emptyList()
        val exo = mockk<ExoPlayer>(relaxed = true) {
            every { bufferedPosition } returns 12_345L
        }

        assertEquals(12_345L, AbsolutePositionPlayer(exo).bufferedPosition)
    }
}
