package com.riffle.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for [SleepTimerDriver] shared between [AudiobookController] (Android) and
 * [IosAudioPlayerController] (iOS). Tests pin the countdown, cancel, triggerNow, and fade-and-stop
 * callback behaviour so that either platform's sleep-timer can't silently regress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerDriverTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun driver(onFadeAndStop: () -> Unit = {}): SleepTimerDriver =
        SleepTimerDriver(scope) { onFadeAndStop() }

    @Test
    fun initialStateIsNone() {
        assertEquals(SleepTimerMode.None, driver().sleepTimer.value)
    }

    @Test
    fun setCountDownPublishesInitialRemaining() = scope.runTest {
        val d = driver()
        d.set(SleepTimerMode.CountDown(5_000L))
        assertEquals(SleepTimerMode.CountDown(5_000L), d.sleepTimer.value)
    }

    @Test
    fun countDownDecrementsEachSecond() = scope.runTest {
        val d = driver()
        d.set(SleepTimerMode.CountDown(3_000L))
        advanceTimeBy(1_001L)
        assertEquals(SleepTimerMode.CountDown(2_000L), d.sleepTimer.value)
        advanceTimeBy(1_000L)
        assertEquals(SleepTimerMode.CountDown(1_000L), d.sleepTimer.value)
    }

    @Test
    fun countDownExpiryCallsFadeAndStop() = scope.runTest {
        var faded = false
        val d = driver { faded = true }
        d.set(SleepTimerMode.CountDown(2_000L))
        advanceTimeBy(3_000L)
        assertTrue(faded, "fadeAndStop must be called when countdown expires")
    }

    @Test
    fun countDownExpiryResetsTimerToNone() = scope.runTest {
        val d = driver()
        d.set(SleepTimerMode.CountDown(1_000L))
        advanceTimeBy(2_000L)
        assertEquals(SleepTimerMode.None, d.sleepTimer.value)
    }

    @Test
    fun countDownExpiryEmitsFired() = scope.runTest {
        var fired = false
        val d = driver()
        val job = launch { d.fired.collect { fired = true } }
        d.set(SleepTimerMode.CountDown(1_000L))
        advanceTimeBy(2_000L)
        assertTrue(fired, "fired must be emitted when countdown expires")
        job.cancel()
    }

    @Test
    fun cancelStopsCountdownAndResetsToNone() = scope.runTest {
        var faded = false
        val d = driver { faded = true }
        d.set(SleepTimerMode.CountDown(5_000L))
        advanceTimeBy(2_000L)
        d.cancel()
        advanceTimeBy(10_000L)
        assertFalse(faded, "fadeAndStop must NOT be called after cancel")
        assertEquals(SleepTimerMode.None, d.sleepTimer.value)
    }

    @Test
    fun triggerNowCallsFadeAndStopImmediately() = scope.runTest {
        var faded = false
        val d = driver { faded = true }
        d.set(SleepTimerMode.CountDown(60_000L))
        d.triggerNow()
        dispatcher.scheduler.runCurrent()
        assertTrue(faded, "fadeAndStop must be called immediately by triggerNow")
    }

    @Test
    fun triggerNowResetsTimerToNone() = scope.runTest {
        val d = driver()
        d.set(SleepTimerMode.CountDown(60_000L))
        d.triggerNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(SleepTimerMode.None, d.sleepTimer.value)
    }

    @Test
    fun setEndOfChapterDoesNotStartCountdown() = scope.runTest {
        var faded = false
        val d = driver { faded = true }
        d.set(SleepTimerMode.EndOfChapter)
        advanceTimeBy(300_000L)
        assertFalse(faded, "EndOfChapter mode must not auto-trigger fadeAndStop")
        assertEquals(SleepTimerMode.EndOfChapter, d.sleepTimer.value)
    }

    @Test
    fun setNoneClearsTimer() = scope.runTest {
        val d = driver()
        d.set(SleepTimerMode.CountDown(5_000L))
        d.set(SleepTimerMode.None)
        assertEquals(SleepTimerMode.None, d.sleepTimer.value)
    }

    @Test
    fun replacingCountdownCancelsOldJob() = scope.runTest {
        var faded = 0
        val d = driver { faded++ }
        d.set(SleepTimerMode.CountDown(5_000L))
        d.set(SleepTimerMode.CountDown(2_000L))
        advanceTimeBy(6_000L)
        assertEquals(1, faded, "Only one fade should fire when countdown is replaced")
    }
}
