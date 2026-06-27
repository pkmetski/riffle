package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeTotalProgressionTest {

    @Test
    fun `returns null for empty spine`() {
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, emptyList(), emptyList()))
    }

    @Test
    fun `returns null when href not in spine`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertNull(computeTotalProgression("ch3.xhtml", 0.5f, spine, counts))
    }

    @Test
    fun `returns null when total positions is zero`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        assertNull(computeTotalProgression("ch1.xhtml", 0.5f, spine, listOf(0, 0)))
    }

    @Test
    fun `first chapter at start`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertEquals(0f, computeTotalProgression("ch1.xhtml", 0f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `first chapter at end`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertEquals(0.5f, computeTotalProgression("ch1.xhtml", 1f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `last chapter at start`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertEquals(0.5f, computeTotalProgression("ch2.xhtml", 0f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `last chapter at end`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertEquals(1f, computeTotalProgression("ch2.xhtml", 1f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `mid-chapter of three equal-weight chapters`() {
        val spine = listOf("a.xhtml", "b.xhtml", "c.xhtml")
        val counts = listOf(100, 100, 100)
        // b at 50% → (100 + 100*0.5) / 300 = 0.5
        assertEquals(0.5f, computeTotalProgression("b.xhtml", 0.5f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `weighted chapters — heavy first chapter`() {
        // ch1=300 positions, ch2=100, total=400
        // ch2 at 0% → 300/400 = 0.75
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(300, 100)
        assertEquals(0.75f, computeTotalProgression("ch2.xhtml", 0f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `single chapter at half`() {
        val spine = listOf("only.xhtml")
        val counts = listOf(200)
        assertEquals(0.5f, computeTotalProgression("only.xhtml", 0.5f, spine, counts)!!, 0.001f)
    }

    @Test
    fun `matches spine entry on base path when href carries a fragment`() {
        val spine = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        assertEquals(
            0.5f,
            computeTotalProgression("ch2.xhtml#start", 0f, spine, counts)!!,
            0.001f,
        )
    }

    // The bug this fix closes: a "Chapter 20" rail segment spanning 3 spine resources used to
    // multiply the within-RESOURCE progression by the whole segment's weight, advancing the
    // chapter map ~3× too fast across the first resource and jamming (null) for the rest.
    // Per-spine-position math makes each resource contribute exactly its share.
    @Test
    fun `multi-resource chapter advances proportionally across all resources`() {
        val spine = listOf("ch20-a.xhtml", "ch20-b.xhtml", "ch20-c.xhtml")
        val counts = listOf(100, 100, 100)

        // 50% through resource A = 50/300 of the book (NOT 50% of "chapter 20" = 150/300).
        assertEquals(50f / 300f, computeTotalProgression("ch20-a.xhtml", 0.5f, spine, counts)!!, 0.001f)
        // The middle resource keeps progressing (used to return null, freezing the cursor).
        assertEquals(150f / 300f, computeTotalProgression("ch20-b.xhtml", 0.5f, spine, counts)!!, 0.001f)
        assertEquals(250f / 300f, computeTotalProgression("ch20-c.xhtml", 0.5f, spine, counts)!!, 0.001f)
    }
}
