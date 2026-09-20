package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Keep screen on while reading" used to be applied as a side effect of its own setter, so it was
 * app-wide and never released. These assertions pin the reader scoping (#1071 §15.3): entering a
 * reader applies the preference, and leaving one always releases it.
 */
class ReaderWakeLockTest {

    private class FakeIdleTimer(override var disabled: Boolean = false) : IdleTimer

    @Test
    fun enteringTheReaderWithThePreferenceOnHoldsTheScreenAwake() {
        val timer = FakeIdleTimer()

        ReaderWakeLock(timer).enterReader(keepScreenOn = true)

        assertTrue(timer.disabled)
    }

    @Test
    fun enteringTheReaderWithThePreferenceOffLeavesTheIdleTimerRunning() {
        val timer = FakeIdleTimer(disabled = true)

        ReaderWakeLock(timer).enterReader(keepScreenOn = false)

        assertFalse(timer.disabled)
    }

    @Test
    fun leavingTheReaderAlwaysReleasesTheScreenEvenWhenThePreferenceIsOn() {
        val timer = FakeIdleTimer()
        val wakeLock = ReaderWakeLock(timer)

        wakeLock.enterReader(keepScreenOn = true)
        wakeLock.leaveReader()

        assertEquals(
            false,
            timer.disabled,
            "the wake lock is scoped to the reader — the library and settings screens must sleep normally",
        )
    }
}
