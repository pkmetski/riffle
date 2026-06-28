package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollDeltaAccumulatorTest {

    @Test
    fun `under one pixel emits zero`() {
        val a = ScrollDeltaAccumulator()
        // 0.5 px/s * 1 s = 0.5 px → 0
        assertEquals(0, a.advance(deltaSec = 1f, pxPerSec = 0.5f))
    }

    @Test
    fun `crossing one pixel emits one pixel`() {
        val a = ScrollDeltaAccumulator()
        // 0.6 px/s * 1 s = 0.6, +0.6 = 1.2 → emit 1, remainder 0.2
        assertEquals(0, a.advance(1f, 0.6f))
        assertEquals(1, a.advance(1f, 0.6f))
    }

    @Test
    fun `whole-pixel jumps are emitted in one call`() {
        val a = ScrollDeltaAccumulator()
        // 13 px/s * 1 s = 13 → emit 13
        assertEquals(13, a.advance(1f, 13f))
    }

    @Test
    fun `fractional remainder accumulates over frames`() {
        val a = ScrollDeltaAccumulator()
        var total = 0
        repeat(60) { total += a.advance(deltaSec = 1f / 60f, pxPerSec = 13f) }
        // 60 * (13/60) = 13 — full pixel total after one second of 60Hz ticks
        assertEquals(13, total)
    }

    @Test
    fun `reset zeroes the accumulator`() {
        val a = ScrollDeltaAccumulator()
        a.advance(1f, 0.6f)
        a.reset()
        assertEquals(0, a.advance(1f, 0.5f))
    }

    @Test
    fun `zero pxPerSec emits nothing`() {
        val a = ScrollDeltaAccumulator()
        assertEquals(0, a.advance(1f, 0f))
    }

    @Test
    fun `negative deltaSec is treated as zero`() {
        val a = ScrollDeltaAccumulator()
        assertEquals(0, a.advance(-1f, 13f))
    }
}
