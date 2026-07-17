package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the on-the-wire form of `TYPE_EMPHASIS.emphasisStyles` (ADR 0046). Any changes here
 * flip the entity's persisted column and the W3C `riffle:styles` extension, so the encoder needs
 * to be deterministic (enum-declaration order, not insertion order) so identical sets always
 * serialize identically — otherwise same-styles equality checks (auto-merge, sync dedup) break.
 */
class EmphasisStyleTest {

    @Test
    fun `encode uses stable enum-declaration order regardless of set insertion order`() {
        val a = linkedSetOf(EmphasisStyle.UNDERLINE, EmphasisStyle.BOLD, EmphasisStyle.STRIKE)
        val b = linkedSetOf(EmphasisStyle.BOLD, EmphasisStyle.STRIKE, EmphasisStyle.UNDERLINE)
        val encodedA = EmphasisStyle.encode(a)
        val encodedB = EmphasisStyle.encode(b)
        assertEquals("bold,underline,strike", encodedA)
        assertEquals(encodedA, encodedB)
    }

    @Test
    fun `encode of empty set returns null (single representation of "no styles")`() {
        assertNull(EmphasisStyle.encode(emptySet()))
    }

    @Test
    fun `decode round-trips every subset back to the same set`() {
        val allSubsets = powerSet(EmphasisStyle.entries.toSet()).filter { it.isNotEmpty() }
        for (subset in allSubsets) {
            val wire = EmphasisStyle.encode(subset)
            val roundTripped = EmphasisStyle.decode(wire)
            assertEquals("round-trip failed for $subset", subset, roundTripped)
        }
    }

    @Test
    fun `decode drops unknown tokens without crashing (forward-compat with future styles)`() {
        // A peer running a newer version could emit "sparkle" alongside known tokens.
        val decoded = EmphasisStyle.decode("bold,sparkle,underline")
        assertEquals(setOf(EmphasisStyle.BOLD, EmphasisStyle.UNDERLINE), decoded)
    }

    @Test
    fun `decode of null blank or unknown-only returns null (no empty set state)`() {
        assertNull(EmphasisStyle.decode(null))
        assertNull(EmphasisStyle.decode(""))
        assertNull(EmphasisStyle.decode("   "))
        assertNull(EmphasisStyle.decode("sparkle,glow"))
    }

    private fun <T> powerSet(set: Set<T>): List<Set<T>> {
        if (set.isEmpty()) return listOf(emptySet())
        val head = set.first()
        val tail = powerSet(set - head)
        return tail + tail.map { it + head }
    }
}
