package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.PauseCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AutoScrollLifecycleTest {

    @Test
    fun backgroundPauseThenForegroundResumeReturnsToRunning() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(300))
        controller.dispatch(AutoScrollEvent.Start)

        controller.pauseIfRunning(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.AppBackgrounded),
            controller.state.value,
        )

        controller.resumeIfPausedBy(PauseCause.AppBackgrounded)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), controller.state.value)
        controller.release()
    }

    @Test
    fun foregroundResumeDoesNotUnparkAUserPause() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(250))
        controller.dispatch(AutoScrollEvent.Start)
        controller.pauseFor(PauseCause.UserPausedPill)

        controller.pauseIfRunning(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(250), PauseCause.UserPausedPill),
            controller.state.value,
        )

        controller.resumeIfPausedBy(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(250), PauseCause.UserPausedPill),
            controller.state.value,
        )
        controller.release()
    }

    @Test
    fun backgroundingDoesNotClobberAnOpenPanelPause() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(250))
        controller.dispatch(AutoScrollEvent.Start)
        controller.pauseFor(PauseCause.PanelOpen)

        // No-op while already paused: overwriting the cause would let foreground un-pause the panel.
        controller.pauseIfRunning(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(250), PauseCause.PanelOpen),
            controller.state.value,
        )

        controller.resumeIfPausedBy(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(250), PauseCause.PanelOpen),
            controller.state.value,
        )

        controller.resumeIfPausedBy(PauseCause.PanelOpen)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(250)), controller.state.value)
        controller.release()
    }

    @Test
    fun pauseIfRunningFromIdleIsANoOp() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.pauseIfRunning(PauseCause.AppBackgrounded)
        assertEquals(AutoScrollState.Idle, controller.state.value)
        controller.release()
    }

    @Test
    fun resumeIfPausedByIgnoresNonMatchingCause() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        controller.pauseFor(PauseCause.PanelOpen)

        controller.resumeIfPausedBy(PauseCause.AppBackgrounded)
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.Default, PauseCause.PanelOpen),
            controller.state.value,
        )
        controller.release()
    }

    @Test
    fun resumeIfPausedByOnIdleIsANoOp() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.resumeIfPausedBy(PauseCause.AppBackgrounded)
        assertEquals(AutoScrollState.Idle, controller.state.value)
        controller.release()
    }
}
