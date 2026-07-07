package com.riffle.core.domain.cadence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArbiterTest {

    @Test
    fun `starting None is a noop`() {
        assertTrue(onStart(Feature.Cadence, Feature.None).isNoop)
        assertTrue(onStart(Feature.None, Feature.None).isNoop)
    }

    @Test
    fun `starting the already-running feature is a noop`() {
        assertTrue(onStart(Feature.Cadence, Feature.Cadence).isNoop)
        assertTrue(onStart(Feature.AutoScroll, Feature.AutoScroll).isNoop)
        assertTrue(onStart(Feature.Readaloud, Feature.Readaloud).isNoop)
    }

    @Test
    fun `starting Cadence while Readaloud is running pauses Readaloud`() {
        assertEquals(
            ArbiterAction(pauseReadaloud = true),
            onStart(Feature.Readaloud, Feature.Cadence),
        )
    }

    @Test
    fun `starting Cadence while Auto-Scroll is running pauses Auto-Scroll`() {
        assertEquals(
            ArbiterAction(pauseAutoScroll = true),
            onStart(Feature.AutoScroll, Feature.Cadence),
        )
    }

    @Test
    fun `starting Readaloud while Cadence is running pauses Cadence`() {
        // Regression: this is the exact symmetric mutual-exclusion the issue #403 acceptance
        // list calls out — "Starting Cadence pauses a running Readaloud, and vice versa".
        assertEquals(
            ArbiterAction(pauseCadence = true),
            onStart(Feature.Cadence, Feature.Readaloud),
        )
    }

    @Test
    fun `starting Auto-Scroll while Cadence is running pauses Cadence`() {
        assertEquals(
            ArbiterAction(pauseCadence = true),
            onStart(Feature.Cadence, Feature.AutoScroll),
        )
    }

    @Test
    fun `starting Cadence while nothing is running is a noop-shaped action`() {
        val action = onStart(Feature.None, Feature.Cadence)
        assertTrue(action.isNoop)
    }
}
