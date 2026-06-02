package com.riffle.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPageTurnRuleTest {

    @Test
    fun `advances when the active sentence is ahead of the viewport`() {
        assertTrue(
            AutoPageTurnRule.shouldAdvance(
                activeIndex = 5,
                visibleIndices = setOf(1, 2, 3),
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `does not advance while the active sentence is still visible`() {
        assertFalse(
            AutoPageTurnRule.shouldAdvance(
                activeIndex = 2,
                visibleIndices = setOf(1, 2, 3),
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `does not advance when the active sentence is behind the viewport`() {
        // user scrolled ahead manually; playback is lagging behind — never page backwards
        assertFalse(
            AutoPageTurnRule.shouldAdvance(
                activeIndex = 0,
                visibleIndices = setOf(3, 4, 5),
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `does not advance when paused`() {
        assertFalse(
            AutoPageTurnRule.shouldAdvance(
                activeIndex = 5,
                visibleIndices = setOf(1, 2, 3),
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `does not advance without an active sentence or visibility info`() {
        assertFalse(AutoPageTurnRule.shouldAdvance(null, setOf(1, 2), isPlaying = true))
        assertFalse(AutoPageTurnRule.shouldAdvance(5, emptySet(), isPlaying = true))
    }
}
