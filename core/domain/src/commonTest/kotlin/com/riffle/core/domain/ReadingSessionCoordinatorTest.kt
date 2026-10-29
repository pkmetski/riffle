package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionCoordinatorTest {

    @Test
    fun `the heartbeat fires onTick on every interval`() = runTest {
        val coord = ReadingSessionCoordinator(this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(onTick = { ticks++ })

        advanceTimeBy(29_999L)
        assertEquals(0, ticks)
        advanceTimeBy(2L) // crosses the first interval boundary
        assertEquals(1, ticks)
        advanceTimeBy(60_000L)
        assertEquals(3, ticks)

        coord.onClosed()
        advanceUntilIdle()
    }

    @Test
    fun `onClosed cancels the heartbeat`() = runTest {
        val coord = ReadingSessionCoordinator(this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(onTick = { ticks++ })

        advanceTimeBy(30_001L)
        assertEquals(1, ticks)
        coord.onClosed()
        advanceTimeBy(60_000L)
        assertEquals(1, ticks)
    }

    @Test
    fun `a second onResumed restarts the heartbeat without leaking the first`() = runTest {
        val coord = ReadingSessionCoordinator(this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(onTick = { ticks++ })
        advanceTimeBy(15_000L)
        coord.onResumed(onTick = { ticks++ })
        advanceTimeBy(15_000L) // would have fired the first job — but it was cancelled
        assertEquals(0, ticks)
        advanceTimeBy(15_001L) // first tick of the new job
        assertEquals(1, ticks)

        coord.onClosed()
        advanceUntilIdle()
    }

    @Test
    fun `the default interval is 30s`() {
        assertEquals(30_000L, ReadingSessionCoordinator.SYNC_INTERVAL_MS)
    }

    @Test
    fun `disabled coordinator does not fire onTick`() = runTest {
        // Regression for #439: a Source without ReadingSessionsCapability must never sync a tick.
        val coord = ReadingSessionCoordinator(
            scope = this,
            syncIntervalMs = 30_000L,
            enabled = { false },
        )
        var ticks = 0
        coord.onResumed(onTick = { ticks++ })

        advanceTimeBy(90_001L)
        assertEquals(0, ticks)

        coord.onClosed()
        advanceUntilIdle()
    }

    @Test
    fun `enabled flipping true after onResumed lets subsequent ticks fire`() = runTest {
        // The reader ViewModels resolve the active Source's Catalog asynchronously — the
        // coordinator's [onResumed] can be called BEFORE the enabled gate has resolved. If the
        // gate were checked once at [onResumed] entry, an ABS session that races the catalog
        // probe would silently drop the entire session's heartbeat. Checking per tick keeps
        // the session alive across the resolve window.
        var enabled = false
        val coord = ReadingSessionCoordinator(
            scope = this,
            syncIntervalMs = 30_000L,
            enabled = { enabled },
        )
        var ticks = 0
        coord.onResumed(onTick = { ticks++ })

        // First tick fires during the resolve window; enabled is still false so it skips.
        advanceTimeBy(30_001L)
        assertEquals(0, ticks)

        // Catalog resolves — subsequent ticks land.
        enabled = true
        advanceTimeBy(30_000L)
        assertEquals(1, ticks)
        advanceTimeBy(30_000L)
        assertEquals(2, ticks)

        coord.onClosed()
        advanceUntilIdle()
    }
}
