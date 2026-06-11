package com.riffle.app.feature.audiobook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePlaybackGateTest {

    @Test
    fun `does not start before the player is ready`() {
        // The regression: starting a resume-at-position open before STATE_READY blips from the
        // track's start. A latched intent must wait for the player to buffer to its position.
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = true, ready = false))
    }

    @Test
    fun `starts once ready when a play intent is latched`() {
        assertTrue(ResumePlaybackGate.shouldStart(wantsToPlay = true, ready = true))
    }

    @Test
    fun `never starts without a play intent, ready or not`() {
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = false, ready = true))
        assertFalse(ResumePlaybackGate.shouldStart(wantsToPlay = false, ready = false))
    }
}
