package com.riffle.core.domain.cadence

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

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

    // ── The fan-out both readers run ──────────────────────────────────────────────────────────
    //
    // `onStart` decided *what* to pause; each host then wrote its own `if` ladder to *do* it,
    // including its own copy of the pause-cause mapping. iOS now has two hands-free features of
    // its own, so that ladder is shared — and these pin it, on both platforms.

    @Test
    fun startingCadenceStopsARunningAutoScrollAndSaysWhy() {
        var autoScrollStopped = false
        val cadencePauses = mutableListOf<PauseCause>()
        runArbiter(
            currentRunning = Feature.AutoScroll,
            starting = Feature.Cadence,
            stopAutoScroll = { autoScrollStopped = true },
            pauseCadence = { cadencePauses += it },
        )
        assertTrue(autoScrollStopped, "starting Cadence must park a running auto-scroll")
        assertTrue(cadencePauses.isEmpty(), "Cadence is the one starting; it must not pause itself")
    }

    @Test
    fun startingAutoScrollPausesCadenceWithTheAutoScrollCause() {
        val cadencePauses = mutableListOf<PauseCause>()
        runArbiter(
            currentRunning = Feature.Cadence,
            starting = Feature.AutoScroll,
            pauseCadence = { cadencePauses += it },
        )
        // The cause is what lets a scoped resume tell "auto-scroll took over" from "the user
        // paused me from the pill" — collapsing it to PanelOpen would let the wrong resume win.
        assertEquals(listOf(PauseCause.AutoScrollStarted), cadencePauses)
    }

    @Test
    fun startingReadaloudPausesCadenceWithTheReadaloudCause() {
        val cadencePauses = mutableListOf<PauseCause>()
        runArbiter(
            currentRunning = Feature.Cadence,
            starting = Feature.Readaloud,
            pauseCadence = { cadencePauses += it },
        )
        assertEquals(listOf(PauseCause.ReadaloudStarted), cadencePauses)
    }

    @Test
    fun aHostWithoutAFeatureLeavesItsHandlerUntouched() {
        // iOS has no reader Readaloud yet; the omitted handler must not change the outcome for
        // the features it does have.
        var autoScrollStopped = false
        runArbiter(
            currentRunning = Feature.AutoScroll,
            starting = Feature.Cadence,
            stopAutoScroll = { autoScrollStopped = true },
        )
        assertTrue(autoScrollStopped)
    }

    @Test
    fun theRunningFeatureSnapshotPrefersCadenceThenAutoScrollThenReadaloud() {
        assertEquals(
            Feature.Cadence,
            currentRunningFeature(cadenceRunning = true, autoScrollRunning = true, readaloudPlaying = true),
        )
        assertEquals(
            Feature.AutoScroll,
            currentRunningFeature(cadenceRunning = false, autoScrollRunning = true, readaloudPlaying = true),
        )
        assertEquals(
            Feature.Readaloud,
            currentRunningFeature(cadenceRunning = false, autoScrollRunning = false, readaloudPlaying = true),
        )
        assertEquals(
            Feature.None,
            currentRunningFeature(cadenceRunning = false, autoScrollRunning = false, readaloudPlaying = false),
        )
    }
}
