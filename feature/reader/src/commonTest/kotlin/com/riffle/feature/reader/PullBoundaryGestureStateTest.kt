package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PullBoundaryGestureStateTest {

    private val density = 1f
    private val threshold = PullBoundaryGestureState.PULL_DISTANCE_THRESHOLD_DP * density
    private val cooldown = PullBoundaryGestureState.NAVIGATION_COOLDOWN_MS

    private fun makeState() = PullBoundaryGestureState(density)

    private fun slowPull(
        state: PullBoundaryGestureState,
        startMs: Long,
        forward: Boolean,
    ): Long {
        val stepDy = if (forward) -3f else 3f
        val stepMs = 50L
        val maxFillRate = PullBoundaryGestureState.MAX_FILL_RATE_DP_PER_MS * density
        val accumPerStep = kotlin.math.abs(stepDy).coerceAtMost(maxFillRate * stepMs)
        val steps = (threshold / accumPerStep).toInt() + 10
        var nowMs = startMs
        repeat(steps) {
            nowMs += stepMs
            state.onMove(
                dy = stepDy,
                atForwardBoundary = forward,
                atBackwardBoundary = !forward,
                canNavigateForward = forward,
                canNavigateBackward = !forward,
                nowMs = nowMs,
            )
        }
        return nowMs
    }

    @Test
    fun forwardPullAccumulatesAndNavigates() {
        val state = makeState()
        val navigated = mutableListOf<Boolean>()
        state.onNavigate = { fwd -> navigated += fwd }

        slowPull(state, START_MS, forward = true)

        assertEquals(1, navigated.size, "Should have navigated once")
        assertTrue(navigated[0], "Should have navigated forward")
    }

    @Test
    fun flingFillRateCapSuppressesBriefPull() {
        val state = makeState()
        val navigated = mutableListOf<Boolean>()
        state.onNavigate = { fwd -> navigated += fwd }

        var nowMs = START_MS
        repeat(5) {
            nowMs += 2L
            state.onMove(
                dy = -threshold / 5,
                atForwardBoundary = true,
                atBackwardBoundary = false,
                canNavigateForward = true,
                canNavigateBackward = false,
                nowMs = nowMs,
            )
        }
        assertEquals(0, navigated.size, "Fling should not trigger navigation")
    }

    @Test
    fun directionReversalResetsPull() {
        val state = makeState()
        var pullEndedCount = 0
        val navigated = mutableListOf<Boolean>()
        state.onNavigate = { fwd -> navigated += fwd }
        state.onPullEnded = { pullEndedCount++ }

        var nowMs = START_MS
        nowMs += 50L
        state.onMove(-3f, atForwardBoundary = true, atBackwardBoundary = false,
            canNavigateForward = true, canNavigateBackward = false, nowMs = nowMs)
        nowMs += 50L
        state.onMove(5f, atForwardBoundary = true, atBackwardBoundary = false,
            canNavigateForward = true, canNavigateBackward = false, nowMs = nowMs)

        assertEquals(0, navigated.size, "Reversed pull should not navigate")
        assertEquals(1, pullEndedCount, "onPullEnded should fire on reset")
    }

    @Test
    fun cooldownPreventsDuplicateNavigation() {
        val state = makeState()
        val navigated = mutableListOf<Boolean>()
        state.onNavigate = { fwd -> navigated += fwd }

        var nowMs = slowPull(state, START_MS, forward = true)
        val navCount = navigated.size
        assertTrue(navCount > 0, "Should have navigated")

        state.reset()
        nowMs = slowPull(state, nowMs, forward = true)
        assertEquals(navCount, navigated.size, "Should not navigate during cooldown")

        nowMs += cooldown + 100
        slowPull(state, nowMs, forward = true)
        assertTrue(navigated.size > navCount, "Should navigate after cooldown expires")
    }

    @Test
    fun noPullWhenCannotNavigateForward() {
        val state = makeState()
        var pullStarted = false
        state.onPullStarted = { pullStarted = true }

        val nowMs = START_MS + 50L
        state.onMove(-3f, atForwardBoundary = true, atBackwardBoundary = false,
            canNavigateForward = false, canNavigateBackward = false, nowMs = nowMs)

        assertFalse(pullStarted, "No pull should start when canNavigateForward=false")
    }

    @Test
    fun backwardPullNavigatesBackward() {
        val state = makeState()
        val navigated = mutableListOf<Boolean>()
        state.onNavigate = { fwd -> navigated += fwd }

        slowPull(state, START_MS, forward = false)

        assertEquals(1, navigated.size)
        assertFalse(navigated[0], "Should have navigated backward")
    }

    @Test
    fun leavingBoundaryMidPullResets() {
        val state = makeState()
        var pullEndedCount = 0
        val navigated = mutableListOf<Boolean>()
        state.onPullEnded = { pullEndedCount++ }
        state.onNavigate = { navigated += it }

        var nowMs = START_MS + 50L
        state.onMove(-3f, true, false, true, false, nowMs)
        nowMs += 50L
        state.onMove(-3f, atForwardBoundary = false, atBackwardBoundary = false,
            canNavigateForward = true, canNavigateBackward = false, nowMs = nowMs)

        assertEquals(0, navigated.size)
        assertEquals(1, pullEndedCount, "onPullEnded should fire when boundary leaves")
    }

    companion object {
        private const val START_MS = 10_000L
    }
}
