package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageScrollCoalescerTest {

    @Test
    fun `first press bases target on current scroll position`() {
        val c = PageScrollCoalescer(validityWindowMs = 300L)
        assertEquals(1000 + 800, c.computeTarget(currentScrollY = 1000, dy = 800, nowMs = 0L))
    }

    @Test
    fun `press inside validity window extends the previous target instead of restarting`() {
        val c = PageScrollCoalescer(validityWindowMs = 300L)
        c.computeTarget(currentScrollY = 1000, dy = 800, nowMs = 0L)
        // Second press arrives mid-animation. Current scrollY has moved to 1400 (60% through the
        // first animation). Without coalescing, target would be 1400 + 800 = 2200 — the animation
        // "resets" and loses the queued extra viewport. With coalescing, target is 1800 + 800 = 2600
        // so the smooth-scroll extends past the previous target without a restart.
        assertEquals(2600, c.computeTarget(currentScrollY = 1400, dy = 800, nowMs = 100L))
    }

    @Test
    fun `press after validity window resets base to current scroll position`() {
        val c = PageScrollCoalescer(validityWindowMs = 300L)
        c.computeTarget(currentScrollY = 1000, dy = 800, nowMs = 0L)
        // First animation is long done; the user's finger position is authoritative again.
        assertEquals(5000 + 800, c.computeTarget(currentScrollY = 5000, dy = 800, nowMs = 1000L))
    }

    @Test
    fun `reset drops the pending target so next press restarts from current`() {
        val c = PageScrollCoalescer(validityWindowMs = 300L)
        c.computeTarget(currentScrollY = 1000, dy = 800, nowMs = 0L)
        c.reset() // e.g. user touched the view to scroll manually
        assertEquals(500 + 800, c.computeTarget(currentScrollY = 500, dy = 800, nowMs = 100L))
    }

    @Test
    fun `backward press inside validity window extends target backwards`() {
        val c = PageScrollCoalescer(validityWindowMs = 300L)
        // First backward press: pending target = 5000 - 800 = 4200.
        c.computeTarget(currentScrollY = 5000, dy = -800, nowMs = 0L)
        // Second press extends from 4200, not from the mid-animation currentScrollY=4600.
        assertEquals(3400, c.computeTarget(currentScrollY = 4600, dy = -800, nowMs = 100L))
    }
}
