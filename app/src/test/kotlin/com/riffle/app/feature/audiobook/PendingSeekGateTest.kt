package com.riffle.app.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the "seek bar blinks to zero for one frame after a track-crossing rewind"
 * bug: after [PendingSeekGate.onSeekIssued], [sample] must return the pending target — NOT the
 * fallback (which, in the real controller, reads the client-side [MediaController] mirror that has
 * optimistically snapped to the raw local-track offsetMs and would be projected as an absurdly early
 * absolute position). If someone reverts the gate, [pendingSampleWinsOverFallback] flips red.
 */
class PendingSeekGateTest {

    @Test
    fun pendingSampleWinsOverFallback() {
        val gate = PendingSeekGate()
        gate.onSeekIssued(absoluteSec = 10_217.0) // ~2:50:17 into a 2:50:45 book
        // Fallback mirrors the raw client-side offsetMs / 1000 (~5:55 into the last track).
        assertEquals(10_217.0, gate.sample { 355.0 }, 0.0)
    }

    /**
     * Regression: skip-forward near the end of a multi-track book flashed the progress bar to
     * chapter 1 for ~0.75s. Root cause: EVENT_POSITION_DISCONTINUITY fired optimistically from the
     * MediaController.seekTo() call with c.currentPosition = perTrackOffsetMs (not book-absolute),
     * and calling onDiscontinuity() there cleared pendingSec, letting the small per-track value
     * win the next sample(). If this test is reverted, that flash returns.
     */
    @Test
    fun maybeConfirmIgnoresOptimisticPerTrackPosition() {
        val gate = PendingSeekGate()
        gate.onSeekIssued(39526.0) // ~10:58:46 book-absolute (near end of 11h book)
        // Optimistic discontinuity: c.currentPosition = perTrackOffsetMs / 1000 = 2775s (not absolute)
        gate.maybeConfirm(2775.0)
        assertEquals(39526.0, gate.sample { 2775.0 }, 0.0)
    }

    @Test
    fun maybeConfirmClearsWhenPositionConverges() {
        val gate = PendingSeekGate()
        gate.onSeekIssued(39526.0)
        // PlayerInfo arrives from session with AbsolutePositionPlayer's projected value
        gate.maybeConfirm(39526.0)
        assertNull(gate.pendingSec)
        assertEquals(39526.1, gate.sample { 39526.1 }, 0.0)
    }

    @Test
    fun resetClearsPending() {
        val gate = PendingSeekGate()
        gate.onSeekIssued(42.0)
        gate.reset()
        assertNull(gate.pendingSec)
        assertEquals(1.0, gate.sample { 1.0 }, 0.0)
    }
}
