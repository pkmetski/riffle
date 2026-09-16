package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumePlaybackGateTest {

    @Test
    fun doesNotStartBeforeThePlayerIsReady() {
        // Regression: starting a resume-at-position open before STATE_READY blips from track start.
        // A latched intent must wait for the player to buffer to its position.
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = true, ready = false))
    }

    @Test
    fun startsOnceReadyWhenAPlayIntentIsLatched() {
        assertTrue(ResumePlaybackGate.shouldStart(wantsToPlay = true, ready = true))
    }

    @Test
    fun neverStartsWithoutAPlayIntentReadyOrNot() {
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = false, ready = true))
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = false, ready = false))
    }
}
