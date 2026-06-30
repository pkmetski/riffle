package com.riffle.app.feature.reader.autoscroll

import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.LayoutContext
import com.riffle.core.domain.autoscroll.PauseCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoScrollControllerTest {

    @Test
    fun `Start enters Running at default speed`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.Default), controller.state.value)
        controller.release()
    }

    @Test
    fun `setDefaultSpeed changes the speed that Start uses`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(320))
        controller.dispatch(AutoScrollEvent.Start)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(320)), controller.state.value)
        controller.release()
    }

    @Test
    fun `Stop returns to Idle`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        controller.dispatch(AutoScrollEvent.Stop)
        assertEquals(AutoScrollState.Idle, controller.state.value)
        controller.release()
    }

    @Test
    fun `Pause then Resume returns to Running at retained speed`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(300))
        controller.dispatch(AutoScrollEvent.Start)
        controller.dispatch(AutoScrollEvent.Pause(PauseCause.PanelOpen))
        assertEquals(
            AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.PanelOpen),
            controller.state.value,
        )
        controller.dispatch(AutoScrollEvent.Resume)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), controller.state.value)
        controller.release()
    }

    @Test
    fun `ticker emits scroll deltas while Running`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(250))
        // Big lineHeight makes px/s sizable enough to emit a whole pixel each ~16ms tick.
        controller.setLayoutContext { LayoutContext(wordsPerLine = 1f, lineHeightPx = 100f) }
        // Inject a deterministic clock tied to virtual time so dt is predictable.
        controller.setClock(object : com.riffle.core.domain.Clock {
            override fun nowMs(): Long = testScheduler.currentTime
            override fun nowNs(): Long = testScheduler.currentTime * 1_000_000L
        })
        controller.dispatch(AutoScrollEvent.Start)

        // Bounded collection: take first N emissions, then stop. Avoids `toList` on a
        // SharedFlow that never completes (which hangs under StandardTestDispatcher even
        // after the producing job is cancelled — slow tests = CI timeout).
        val taken = withTimeout(5_000L) {
            controller.scrollDeltas.take(5).toList()
        }
        controller.dispatch(AutoScrollEvent.Stop)
        controller.release()

        assertEquals(5, taken.size)
        assertTrue("each delta should be a whole positive pixel", taken.all { it >= 1 })
    }

    @Test
    fun `ticker stops emitting after Stop is dispatched`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setLayoutContext { LayoutContext(wordsPerLine = 1f, lineHeightPx = 100f) }
        controller.setClock(object : com.riffle.core.domain.Clock {
            override fun nowMs(): Long = testScheduler.currentTime
            override fun nowNs(): Long = testScheduler.currentTime * 1_000_000L
        })
        controller.dispatch(AutoScrollEvent.Start)
        // Drain one emission to confirm the ticker is alive.
        withTimeout(5_000L) { controller.scrollDeltas.first() }
        controller.dispatch(AutoScrollEvent.Stop)
        assertEquals(AutoScrollState.Idle, controller.state.value)
        // Advancing well past the next 60Hz tick produces no more emissions because the
        // ticker was cancelled by Stop. If it hadn't, `first()` would resolve and pass —
        // we want it to time out, which means the ticker is silent.
        var leaked = false
        try {
            withTimeout(200L) { controller.scrollDeltas.first(); leaked = true }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // expected — no emissions
        }
        controller.release()
        assertTrue("expected no emissions after Stop, but the ticker leaked one", !leaked)
    }
}
