package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerFormatTest {

    // ── formatCountdown ──────────────────────────────────────────────────────────

    @Test
    fun `formatCountdown formats minutes and seconds`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 30 * 60 * 1_000L)
        assertEquals("30:00", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown pads seconds with leading zero`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 5 * 60 * 1_000L + 7_000L)
        assertEquals("5:07", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown returns empty string for non-CountDown mode`() {
        assertEquals("", SleepTimerMode.None.formatCountdown())
        assertEquals("", SleepTimerMode.EndOfChapter.formatCountdown())
    }

    @Test
    fun `formatCountdown handles sub-minute duration`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 30_000L)
        assertEquals("0:30", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown handles zero remaining`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 0L)
        assertEquals("0:00", mode.formatCountdown())
    }

    @Test
    fun formatCountdownZeroPadsSecondsButNotMinutes() {
        assertEquals("1:05", SleepTimerMode.CountDown(remainingMs = 65_000).formatCountdown())
        assertEquals("0:05", SleepTimerMode.CountDown(remainingMs = 5_000).formatCountdown())
        assertEquals("1:00", SleepTimerMode.CountDown(remainingMs = 60_000).formatCountdown())
    }

    @Test
    fun formatCountdownHandlesOverAnHourAndExhaustedTimers() {
        assertEquals("60:00", SleepTimerMode.CountDown(remainingMs = 3_600_000).formatCountdown())
        assertEquals("0:00", SleepTimerMode.CountDown(remainingMs = 0).formatCountdown())
    }

    @Test
    fun formatCountdownTruncatesPartialSeconds() {
        assertEquals("0:01", SleepTimerMode.CountDown(remainingMs = 1_999).formatCountdown())
    }

    // ── AudiobookPlayIntentRegistry ──────────────────────────────────────────────

    @Test
    fun consumeIntentReturnsFalseWhenNoIntentWasRecorded() {
        val registry = AudiobookPlayIntentRegistry()
        assertFalse(registry.consumeIntent("src-1", "item-1"))
    }

    @Test
    fun recordedIntentIsConsumedOnce() {
        val registry = AudiobookPlayIntentRegistry()
        registry.recordIntent("src-1", "item-1")
        assertTrue(registry.consumeIntent("src-1", "item-1"))
        assertFalse(registry.consumeIntent("src-1", "item-1"))
    }

    @Test
    fun intentsAreScopedPerItem() {
        val registry = AudiobookPlayIntentRegistry()
        registry.recordIntent("src-1", "item-1")
        assertFalse(registry.consumeIntent("src-1", "item-2"))
        assertFalse(registry.consumeIntent("src-2", "item-1"))
        assertTrue(registry.consumeIntent("src-1", "item-1"))
    }
}
