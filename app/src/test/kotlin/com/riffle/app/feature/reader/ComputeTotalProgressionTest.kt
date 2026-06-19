package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeTotalProgressionTest {

    private fun seg(href: String, weight: Float) = RailSegment(title = href, href = href, weight = weight)

    @Test
    fun `returns null for empty segments`() {
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, emptyList()))
    }

    @Test
    fun `returns null when href not in segments`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertNull(computeTotalProgression("ch3.xhtml", 0.5f, segments))
    }

    @Test
    fun `returns null when total weight is zero`() {
        val segments = listOf(seg("ch1.xhtml", 0f), seg("ch2.xhtml", 0f))
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, segments))
    }

    @Test
    fun `first chapter at start`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0f, computeTotalProgression("ch1.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `first chapter at end`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0.5f, computeTotalProgression("ch1.xhtml", 1f, segments)!!, 0.001f)
    }

    @Test
    fun `last chapter at start`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(0.5f, computeTotalProgression("ch2.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `last chapter at end`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        assertEquals(1f, computeTotalProgression("ch2.xhtml", 1f, segments)!!, 0.001f)
    }

    @Test
    fun `mid-chapter of three equal-weight chapters`() {
        val segments = listOf(seg("a.xhtml", 1f), seg("b.xhtml", 1f), seg("c.xhtml", 1f))
        // b at 50% → (1 + 1*0.5) / 3 = 0.5
        assertEquals(0.5f, computeTotalProgression("b.xhtml", 0.5f, segments)!!, 0.001f)
    }

    @Test
    fun `weighted chapters — heavy first chapter`() {
        // ch1 weight=3, ch2 weight=1, total=4
        // ch2 at 0% → cumulativeWeight=3, result = 3/4 = 0.75
        val segments = listOf(seg("ch1.xhtml", 3f), seg("ch2.xhtml", 1f))
        assertEquals(0.75f, computeTotalProgression("ch2.xhtml", 0f, segments)!!, 0.001f)
    }

    @Test
    fun `single chapter at 50%`() {
        val segments = listOf(seg("only.xhtml", 2f))
        assertEquals(0.5f, computeTotalProgression("only.xhtml", 0.5f, segments)!!, 0.001f)
    }

    @Test
    fun `matches segment whose href has fragment when caller passes bare path`() {
        // TOC entries often store hrefs with #fragment (e.g. "ch1.xhtml#intro"),
        // while ContinuousReaderView reports the spine href without fragment.
        val segments = listOf(
            RailSegment(title = "ch1", href = "ch1.xhtml#intro", weight = 1f),
            RailSegment(title = "ch2", href = "ch2.xhtml#start", weight = 1f),
        )
        // bare "ch2.xhtml" should match "ch2.xhtml#start"
        assertEquals(0.5f, computeTotalProgression("ch2.xhtml", 0f, segments)!!, 0.001f)
    }
}
