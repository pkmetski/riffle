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

    @Test
    fun fallbackReturnsAfterDiscontinuity() {
        val gate = PendingSeekGate()
        gate.onSeekIssued(10_217.0)
        gate.onDiscontinuity()
        assertNull(gate.pendingSec)
        assertEquals(10_217.0, gate.sample { 10_217.0 }, 0.0)
        // Confirm subsequent samples read the fallback each time.
        assertEquals(10_218.5, gate.sample { 10_218.5 }, 0.0)
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
