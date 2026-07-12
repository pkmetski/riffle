package com.riffle.app.feature.source.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToggleTest {
    @Test
    fun `collapsing an open search clears the query and returns closed`() {
        var cleared = false
        val next = toggleSearchOpen(currentlyOpen = true) { cleared = true }
        assertFalse(next)
        assertTrue("expected clearQuery() to be invoked on collapse", cleared)
    }

    @Test
    fun `opening a closed search does not clear the query and returns open`() {
        var cleared = false
        val next = toggleSearchOpen(currentlyOpen = false) { cleared = true }
        assertTrue(next)
        assertFalse("clearQuery() must not fire when opening the field", cleared)
    }

    @Test
    fun `collapse invokes clearQuery exactly once`() {
        var calls = 0
        toggleSearchOpen(currentlyOpen = true) { calls += 1 }
        assertEquals(1, calls)
    }
}
