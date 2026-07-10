package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionCoordinatorTest {

    private class FakeSpeedStore(initial: Double = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION) : ReadingSpeedStore {
        private val flow = MutableStateFlow(initial)
        override val speedSecPerPosition: Flow<Double> = flow.asStateFlow()
        override suspend fun updateSpeed(newSecPerPosition: Double) { flow.value = newSecPerPosition }
        val current: Double get() = flow.value
    }

    @Test
    fun `the heartbeat fires onTick on every interval`() = runTest {
        val clock = TestClock()
        val coord = ReadingSessionCoordinator(clock, FakeSpeedStore(), this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(initialTotalProgression = 0f, onTick = { ticks++ })

        advanceTimeBy(29_999L)
        assertEquals(0, ticks)
        advanceTimeBy(2L) // crosses the first interval boundary
        assertEquals(1, ticks)
        advanceTimeBy(60_000L)
        assertEquals(3, ticks)

        coord.onClosed(currentTotalProgression = 0f, totalPositions = 0f)
        advanceUntilIdle()
    }

    @Test
    fun `onClosed cancels the heartbeat`() = runTest {
        val clock = TestClock()
        val coord = ReadingSessionCoordinator(clock, FakeSpeedStore(), this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(initialTotalProgression = 0f, onTick = { ticks++ })

        advanceTimeBy(30_001L)
        assertEquals(1, ticks)
        coord.onClosed(currentTotalProgression = 0f, totalPositions = 0f)
        advanceTimeBy(60_000L)
        assertEquals(1, ticks)
    }

    @Test
    fun `a second onResumed restarts the heartbeat without leaking the first`() = runTest {
        val clock = TestClock()
        val coord = ReadingSessionCoordinator(clock, FakeSpeedStore(), this, syncIntervalMs = 30_000L)
        var ticks = 0
        coord.onResumed(initialTotalProgression = 0f, onTick = { ticks++ })
        advanceTimeBy(15_000L)
        coord.onResumed(initialTotalProgression = 0.1f, onTick = { ticks++ })
        advanceTimeBy(15_000L) // would have fired the first job — but it was cancelled
        assertEquals(0, ticks)
        advanceTimeBy(15_001L) // first tick of the new job
        assertEquals(1, ticks)

        coord.onClosed(currentTotalProgression = 0.1f, totalPositions = 0f)
        advanceUntilIdle()
    }

    @Test
    fun `onClosed feeds the speed tracker and persists the updated speed`() = runTest {
        val clock = TestClock(initialMs = 1_000L)
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = 0.10f, onTick = {})

        // 600s elapsed, progressDelta = 0.05, totalPositions = 100 → positionsDelta = 5 → 120 s/pos.
        // ALPHA=0.2: 0.2*120 + 0.8*100 = 104.0
        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(104.0, store.current, 1e-5)
    }

    @Test
    fun `onClosed without an initialTotalProgression skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = null, onTick = {})

        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.5f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `onClosed with zero totalPositions skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = 0f, onTick = {})

        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.5f, totalPositions = 0f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `onClosed with no current progression skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = 0.1f, onTick = {})

        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = null, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `a second onClosed after a flush is a no-op`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = 0.10f, onTick = {})

        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()
        val afterFirst = store.current

        coord.onClosed(currentTotalProgression = 0.20f, totalPositions = 100f)
        advanceUntilIdle()
        assertEquals(afterFirst, store.current, 1e-9)
    }

    @Test
    fun `onClosed without a prior onResumed is a no-op`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)

        coord.onClosed(currentTotalProgression = 0.5f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `a too-short session yields no speed update`() = runTest {
        // ReadingSpeedTracker requires at least MIN_SESSION_SEC=30s of elapsed time.
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(clock, store, this, syncIntervalMs = 30_000L)
        coord.onResumed(initialTotalProgression = 0.10f, onTick = {})

        clock.advance(10_000L)
        coord.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `the default interval is 30s`() {
        assertEquals(30_000L, ReadingSessionCoordinator.SYNC_INTERVAL_MS)
    }

    @Test
    fun `disabled coordinator does not fire onTick and does not flush speed`() = runTest {
        // Regression for #439: a Source without ReadingSessionsCapability must never sync a
        // reading tick or update the shared speed store. The tick job itself may spin (that's
        // free) but the observable behaviour — onTick calls and speed writes — must be zero.
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val coord = ReadingSessionCoordinator(
            clock = clock,
            readingSpeedStore = store,
            scope = this,
            syncIntervalMs = 30_000L,
            enabled = { false },
        )
        var ticks = 0
        coord.onResumed(initialTotalProgression = 0.10f, onTick = { ticks++ })

        advanceTimeBy(90_001L)
        assertEquals(0, ticks)

        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()
        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `enabled flipping true after onResumed lets subsequent ticks fire`() = runTest {
        // The reader ViewModels resolve the active Source's Catalog asynchronously — the
        // coordinator's [onResumed] can be called BEFORE the enabled gate has resolved. If the
        // gate were checked once at [onResumed] entry, an ABS session that races the catalog
        // probe would silently drop the entire session's heartbeat. Checking per tick keeps
        // the session alive across the resolve window.
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        var enabled = false
        val coord = ReadingSessionCoordinator(
            clock = clock,
            readingSpeedStore = store,
            scope = this,
            syncIntervalMs = 30_000L,
            enabled = { enabled },
        )
        var ticks = 0
        coord.onResumed(initialTotalProgression = 0.10f, onTick = { ticks++ })

        // First tick fires during the resolve window; enabled is still false so it skips.
        advanceTimeBy(30_001L)
        assertEquals(0, ticks)

        // Catalog resolves — subsequent ticks land.
        enabled = true
        advanceTimeBy(30_000L)
        assertEquals(1, ticks)
        advanceTimeBy(30_000L)
        assertEquals(2, ticks)

        // Flush the started session so it also runs the speed write once at close.
        clock.advance(600_000L)
        coord.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()
    }
}
