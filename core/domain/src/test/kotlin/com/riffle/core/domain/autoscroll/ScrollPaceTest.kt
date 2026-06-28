package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ScrollPaceTest {

    private fun nearly(expected: Float, actual: Float, eps: Float = 0.05f) {
        assert(abs(expected - actual) < eps) { "expected ≈$expected, got $actual" }
    }

    private val typicalLayout = LayoutContext(wordsPerLine = 9f, lineHeightPx = 28f)

    @Test
    fun `default 250 wpm in typical layout is roughly 13 px per second`() {
        // (250 / 60) * (28 / 9) ≈ 12.96 px/s
        nearly(12.96f, pxPerSecond(AutoScrollSpeed.Default, typicalLayout))
    }

    @Test
    fun `doubling wpm doubles px per second`() {
        val a = pxPerSecond(AutoScrollSpeed.of(150), typicalLayout)
        val b = pxPerSecond(AutoScrollSpeed.of(300), typicalLayout)
        nearly(2f * a, b)
    }

    @Test
    fun `larger line height increases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 56f))
        nearly(2f * a, b)
    }

    @Test
    fun `more words per line decreases px per second proportionally`() {
        val a = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(9f, 28f))
        val b = pxPerSecond(AutoScrollSpeed.of(250), LayoutContext(18f, 28f))
        nearly(a / 2f, b)
    }

    @Test
    fun `zero words per line yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(0f, 28f)), 0.0001f)
    }

    @Test
    fun `zero line height yields zero pace`() {
        assertEquals(0f, pxPerSecond(AutoScrollSpeed.Default, LayoutContext(9f, 0f)), 0.0001f)
    }
}
