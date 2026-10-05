package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression tests for: tapping a chapter from the chapter list while paused must start playback.
 *
 * Root cause: AudiobookPlayerViewModel.seekTo called controller.seekTo but never controller.play,
 * so a chapter tap while paused moved the playhead but left the player paused.
 *
 * Fix: seekAndResumeIfPaused (called by seekTo, previousChapter, nextChapter) calls play() when
 * the player is not already playing. This test pins that decision without requiring the full VM.
 *
 * Runs on JVM and iosSimulatorArm64 via commonTest.
 */
class ChapterSeekAutoPlayTest {

    @Test
    fun seekWhilePausedCallsPlayAfterSeek() {
        val order = mutableListOf<String>()
        seekAndResumeIfPaused(
            isPlaying = false,
            seek = { order += "seek" },
            play = { order += "play" },
        )
        assertEquals(listOf("seek", "play"), order,
            "chapter tap while paused must seek then start playback")
    }

    @Test
    fun seekWhileAlreadyPlayingDoesNotCallPlay() {
        var playCalled = false
        seekAndResumeIfPaused(
            isPlaying = true,
            seek = {},
            play = { playCalled = true },
        )
        assertFalse(playCalled, "chapter tap while playing must not call play() a second time")
    }
}
