package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test
import com.riffle.core.domain.TestClock

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSpeedSessionTest {

    private class FakeSpeedStore(initial: Double = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION) : ReadingSpeedStore {
        private val flow = MutableStateFlow(initial)
        override val speedSecPerPosition: Flow<Double> = flow.asStateFlow()
        override suspend fun updateSpeed(newSecPerPosition: Double) { flow.value = newSecPerPosition }
        val current: Double get() = flow.value
    }

    @Test
    fun `onClosed feeds the speed tracker and persists the updated speed`() = runTest {
        val clock = TestClock(initialMs = 1_000L)
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = 0.10f)

        // 600s elapsed, progressDelta = 0.05, totalPositions = 100 → positionsDelta = 5 → 120 s/pos.
        // ALPHA=0.2: 0.2*120 + 0.8*100 = 104.0
        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(104.0, store.current, 1e-5)
    }

    @Test
    fun `onClosed without an initialTotalProgression skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = null)

        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = 0.5f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `onClosed with zero totalPositions skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = 0f)

        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = 0.5f, totalPositions = 0f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `onClosed with no current progression skips the speed write`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = 0.1f)

        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = null, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `a second onClosed after a flush is a no-op`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = 0.10f)

        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()
        val afterFirst = store.current

        session.onClosed(currentTotalProgression = 0.20f, totalPositions = 100f)
        advanceUntilIdle()
        assertEquals(afterFirst, store.current, 1e-9)
    }

    @Test
    fun `onClosed without a prior onResumed is a no-op`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)

        session.onClosed(currentTotalProgression = 0.5f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `a too-short session yields no speed update`() = runTest {
        // ReadingSpeedTracker requires at least MIN_SESSION_SEC=30s of elapsed time.
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this)
        session.onResumed(initialTotalProgression = 0.10f)

        clock.advance(10_000L)
        session.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()

        assertEquals(100.0, store.current, 1e-9)
    }

    @Test
    fun `disabled session does not flush speed`() = runTest {
        val clock = TestClock()
        val store = FakeSpeedStore(initial = 100.0)
        val session = ReadingSpeedSession(clock, store, this, enabled = { false })
        session.onResumed(initialTotalProgression = 0.10f)

        clock.advance(600_000L)
        session.onClosed(currentTotalProgression = 0.15f, totalPositions = 100f)
        advanceUntilIdle()
        assertEquals(100.0, store.current, 1e-9)
    }
}
