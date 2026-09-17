package com.riffle.feature.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShouldShowRiffleSourceTest {

    @Test
    fun `Riffle source hidden when no sources configured`() {
        assertFalse(shouldShowRiffleSource(sourceCount = 0))
    }

    @Test
    fun `Riffle source hidden when exactly one source configured`() {
        assertFalse(shouldShowRiffleSource(sourceCount = 1))
    }

    @Test
    fun `Riffle source shown when exactly two sources configured`() {
        assertTrue(shouldShowRiffleSource(sourceCount = 2))
    }

    @Test
    fun `Riffle source shown when more than two sources configured`() {
        assertTrue(shouldShowRiffleSource(sourceCount = 5))
    }
}
