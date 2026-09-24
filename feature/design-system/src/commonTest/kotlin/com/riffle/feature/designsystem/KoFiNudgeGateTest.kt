package com.riffle.feature.designsystem

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the seenBelowThreshold/shownThisSession gate inside collectKoFiProgressionNudge.
 * Runs on both Android (androidHostTest) and iOS (iosSimulatorArm64Test) — the gate is
 * the critical correctness invariant for the Ko-fi nudge on both platforms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KoFiNudgeGateTest {

    @Test
    fun highInitialProgressionDoesNotTrigger() = runTest {
        val progression = MutableStateFlow<Float?>(0.987f)
        var triggered = false

        backgroundScope.launch { collectKoFiProgressionNudge(progression) { triggered = true } }
        runCurrent()

        assertFalse(triggered, "nudge must not fire when book opens at a saved high position")
    }

    @Test
    fun progressionCrossesFromBelowThenTriggersOnce() = runTest {
        val progression = MutableStateFlow<Float?>(0.5f)
        var triggerCount = 0

        backgroundScope.launch { collectKoFiProgressionNudge(progression) { triggerCount++ } }
        runCurrent()
        assertFalse(triggerCount > 0)

        progression.value = 0.99f
        runCurrent()

        assertTrue(triggerCount == 1, "nudge must fire exactly once after crossing the threshold")
    }

    @Test
    fun nudgeDoesNotFireAgainAfterFirstTrigger() = runTest {
        val progression = MutableStateFlow<Float?>(0.5f)
        var triggerCount = 0

        backgroundScope.launch { collectKoFiProgressionNudge(progression) { triggerCount++ } }
        runCurrent()

        progression.value = 0.99f
        runCurrent()
        assertTrue(triggerCount == 1)

        progression.value = 0.3f
        runCurrent()
        progression.value = 0.99f
        runCurrent()

        assertTrue(triggerCount == 1, "shownThisSession must prevent the nudge from re-firing")
    }

    @Test
    fun nullProgressionIsIgnored() = runTest {
        val progression = MutableStateFlow<Float?>(null)
        var triggered = false

        backgroundScope.launch { collectKoFiProgressionNudge(progression) { triggered = true } }
        runCurrent()

        progression.value = null
        runCurrent()

        assertFalse(triggered, "null progression must never trigger the nudge")
    }

    @Test
    fun nudgeFiresOnlyAfterSubThresholdEmissionNotJustAtThreshold() = runTest {
        val progression = MutableStateFlow<Float?>(0.97f)
        var triggered = false

        backgroundScope.launch { collectKoFiProgressionNudge(progression) { triggered = true } }
        runCurrent()
        assertFalse(triggered)

        progression.value = 0.99f
        runCurrent()

        assertTrue(triggered, "first emission below threshold followed by crossing must trigger")
    }
}
