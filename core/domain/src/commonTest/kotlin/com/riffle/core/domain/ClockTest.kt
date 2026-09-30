package com.riffle.core.domain

import com.riffle.core.common.platformSystemClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockTest {

    @Test
    fun `TestClock returns the initial value and advances by delta`() {
        val clock = TestClock(initialMs = 1_000L)

        assertEquals(1_000L, clock.nowMs())
        assertEquals(1_000_000_000L, clock.nowNs())

        clock.advance(500L)
        assertEquals(1_500L, clock.nowMs())
        assertEquals(1_500_000_000L, clock.nowNs())
    }

    @Test
    fun `TestClock setNowMs jumps to absolute instant`() {
        val clock = TestClock()
        clock.setNowMs(42L)
        assertEquals(42L, clock.nowMs())
        assertEquals(42_000_000L, clock.nowNs())
    }

    @Test
    fun `platformSystemClock returns monotonically non-decreasing nanos`() {
        val first = platformSystemClock.nowNs()
        val second = platformSystemClock.nowNs()
        // nanoTime is monotonic; same-tick reads tie, never regress.
        assertTrue(second >= first, "expected monotonic nanos, got first=$first second=$second")
    }
}
