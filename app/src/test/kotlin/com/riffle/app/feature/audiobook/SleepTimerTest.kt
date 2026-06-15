package com.riffle.app.feature.audiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

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

    // ── FakeController ───────────────────────────────────────────────────────────

    private class FakeController : AudiobookController() {
        private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
        override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

        val setSleepTimerCalls = mutableListOf<SleepTimerMode>()
        var cancelCalled = 0
        var triggerNowCalled = 0

        override fun setSleepTimer(mode: SleepTimerMode) {
            setSleepTimerCalls.add(mode)
            _sleepTimer.value = mode
        }

        override fun cancelSleepTimer() {
            cancelCalled++
            _sleepTimer.value = SleepTimerMode.None
        }

        override fun triggerSleepNow() {
            triggerNowCalled++
            _sleepTimer.value = SleepTimerMode.None
        }
    }

    // ── delegation tests ─────────────────────────────────────────────────────────

    @Test
    fun `setSleepTimer delegates to controller with CountDown mode`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))

        assertEquals(1, controller.setSleepTimerCalls.size)
        assertTrue(controller.setSleepTimerCalls[0] is SleepTimerMode.CountDown)
        assertEquals(30 * 60_000L, (controller.setSleepTimerCalls[0] as SleepTimerMode.CountDown).remainingMs)
    }

    @Test
    fun `setSleepTimer delegates to controller with EndOfChapter mode`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.EndOfChapter)

        assertEquals(SleepTimerMode.EndOfChapter, controller.setSleepTimerCalls[0])
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
    }

    @Test
    fun `cancelSleepTimer resets timer to None`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        controller.cancelSleepTimer()

        assertEquals(1, controller.cancelCalled)
        assertEquals(SleepTimerMode.None, controller.sleepTimer.value)
    }

    @Test
    fun `setting a new timer replaces the previous one`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        controller.setSleepTimer(SleepTimerMode.EndOfChapter)

        assertEquals(2, controller.setSleepTimerCalls.size)
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
    }
}
