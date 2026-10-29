package com.riffle.app.feature.audiobook

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the "previously paused audio restarts on return from background" bug.
 *
 * Root cause: [AudiobookController] uses a [wantsToPlay] flag to defer play() until the player
 * reaches STATE_READY. Two paths failed to clear the flag before a pause():
 *
 *  1. External pause (notification, headphone removal, audio-focus loss) — does not call
 *     [AudiobookController.pause], so [wantsToPlay] stayed true and the subsequent STATE_READY
 *     event fired playback. Fixed by clearing [wantsToPlay] in the onEvents listener when
 *     EVENT_IS_PLAYING_CHANGED fires with isPlaying = false.
 *
 *  2. [releaseForHandoff] called controller.pause() before setting [wantsToPlay] = false —
 *     the Player.Listener could restart playback via maybeStart() in the brief window between
 *     the two. Fixed by moving [wantsToPlay] = false before pause(), matching the sleepDriver.
 */
class AudiobookControllerPlaybackResumeTest {

    private val controller = ConcreteAudiobookController()

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun readyPlayer(isPlaying: Boolean = false): Player = mockk(relaxed = true) {
        every { playbackState } returns Player.STATE_READY
        every { this@mockk.isPlaying } returns isPlaying
    }

    // ── tests ─────────────────────────────────────────────────────────────────────

    @Test
    fun playSetsPendingPlayIntent() {
        controller.play()

        assertTrue(controller.wantsToPlay)
    }

    @Test
    fun pauseClearsPendingPlayIntent() {
        controller.play()
        controller.pause()

        assertFalse(controller.wantsToPlay)
    }

    @Test
    fun releaseForHandoffClearsPendingPlayIntentBeforePauseEvent() {
        controller.play()

        controller.releaseForHandoff()

        // wantsToPlay must be false after releaseForHandoff so that a STATE_READY event from
        // the still-live player cannot fire maybeStart() and restart audio.
        assertFalse(controller.wantsToPlay)
    }

    @Test
    fun externalPauseClearsPendingPlayIntentSoStateReadyDoesNotRestart() {
        val player = readyPlayer(isPlaying = false)
        controller.play()

        // Simulate an external pause (notification button, headphone removal, audio focus loss)
        // by firing the listener with EVENT_IS_PLAYING_CHANGED and isPlaying = false.
        controller.playerListenerForTest.onEvents(
            player,
            mockk(relaxed = true) {
                every { contains(Player.EVENT_IS_PLAYING_CHANGED) } returns true
                every { contains(Player.EVENT_PLAYBACK_STATE_CHANGED) } returns false
                every { containsAny(any(), any()) } returns true
            },
        )

        // wantsToPlay must be cleared so a later STATE_READY event cannot restart playback.
        assertFalse(controller.wantsToPlay)
    }

    @Test
    fun stateReadyAfterPlayFiresPlayerPlay() {
        val player = readyPlayer()
        controller.play()

        controller.playerListenerForTest.onEvents(
            player,
            mockk(relaxed = true) {
                every { contains(Player.EVENT_IS_PLAYING_CHANGED) } returns false
                every { contains(Player.EVENT_PLAYBACK_STATE_CHANGED) } returns true
                every { containsAny(any(), any()) } returns true
            },
        )

        verify(exactly = 1) { player.play() }
    }

    @Test
    fun stateReadyAfterExternalPauseDoesNotFirePlayerPlay() {
        val player = readyPlayer(isPlaying = false)
        controller.play()

        // External pause clears wantsToPlay.
        controller.playerListenerForTest.onEvents(
            player,
            mockk(relaxed = true) {
                every { contains(Player.EVENT_IS_PLAYING_CHANGED) } returns true
                every { contains(Player.EVENT_PLAYBACK_STATE_CHANGED) } returns false
                every { containsAny(any(), any()) } returns true
            },
        )

        // STATE_READY fires (player finished buffering at seek position).
        controller.playerListenerForTest.onEvents(
            player,
            mockk(relaxed = true) {
                every { contains(Player.EVENT_IS_PLAYING_CHANGED) } returns false
                every { contains(Player.EVENT_PLAYBACK_STATE_CHANGED) } returns true
                every { containsAny(any(), any()) } returns true
            },
        )

        verify(exactly = 0) { player.play() }
    }

    // ── minimal concrete subclass ─────────────────────────────────────────────────

    private class ConcreteAudiobookController : AudiobookController()
}
