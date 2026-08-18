package com.riffle.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperOptionsTapCounterTest {

    @Test
    fun `seven taps triggers unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(6) { counter.onTap() }
        assertEquals("not yet unlocked", 0, unlockCalls)
        counter.onTap()
        assertEquals("unlocked on 7th tap", 1, unlockCalls)
    }

    @Test
    fun `counter resets after unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(7) { counter.onTap() }
        repeat(7) { counter.onTap() }
        assertEquals("unlocked twice", 2, unlockCalls)
    }

    @Test
    fun `partial taps do not trigger unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(6) { counter.onTap() }
        assertEquals(0, unlockCalls)
    }
}
