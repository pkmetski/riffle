package com.riffle.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class DeveloperOptionsTapCounterTest {

    @Test
    fun `seven taps triggers unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(6) { counter.onTap() }
        assertEquals(0, unlockCalls, "not yet unlocked")
        counter.onTap()
        assertEquals(1, unlockCalls, "unlocked on 7th tap")
    }

    @Test
    fun `counter resets after unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(7) { counter.onTap() }
        repeat(7) { counter.onTap() }
        assertEquals(2, unlockCalls, "unlocked twice")
    }

    @Test
    fun `partial taps do not trigger unlock`() {
        var unlockCalls = 0
        val counter = DeveloperOptionsTapCounter(requiredTaps = 7, onUnlock = { unlockCalls++ })
        repeat(6) { counter.onTap() }
        assertEquals(0, unlockCalls)
    }
}
