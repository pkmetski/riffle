package com.riffle.app.feature.reader.autoscroll

import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.LayoutContext
import com.riffle.core.domain.autoscroll.PauseCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
    }

    @Test
    fun `setDefaultSpeed changes the speed that Start uses`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(320))
        controller.dispatch(AutoScrollEvent.Start)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(320)), controller.state.value)
    }

    @Test
    fun `Stop returns to Idle`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        controller.dispatch(AutoScrollEvent.Stop)
        assertEquals(AutoScrollState.Idle, controller.state.value)
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
    }

    @Test
    fun `ticker emits scroll deltas while Running`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(250))
        // Big lineHeight makes px/s sizable enough to emit a whole pixel each ~16ms tick.
        controller.setLayoutContext { LayoutContext(wordsPerLine = 1f, lineHeightPx = 100f) }
        // Inject a deterministic clock tied to virtual time so dt is predictable.
        controller.setClock { testScheduler.currentTime * 1_000_000L }

        val collected = mutableListOf<Int>()
        val job = launch { controller.scrollDeltas.toList(collected) }

        controller.dispatch(AutoScrollEvent.Start)
        // Run for ~1 second of virtual time (60 frames).
        advanceTimeBy(1_000L)
        controller.dispatch(AutoScrollEvent.Stop)

        job.cancel()
        // At 250 wpm with our exaggerated layout, px/s = (250/60) * (100/1) ≈ 416.6 px/s.
        // Over 1s of ticks we expect the total emitted pixels to be in that ballpark.
        val total = collected.sum()
        assertTrue("expected ~400px in 1s, got $total", total in 300..500)
    }

    @Test
    fun `ticker is cancelled when Stop is dispatched`() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setLayoutContext { LayoutContext(wordsPerLine = 1f, lineHeightPx = 100f) }
        controller.setClock { testScheduler.currentTime * 1_000_000L }
        val collected = mutableListOf<Int>()
        val job = launch { controller.scrollDeltas.toList(collected) }

        controller.dispatch(AutoScrollEvent.Start)
        advanceTimeBy(200L)
        controller.dispatch(AutoScrollEvent.Stop)
        val frozen = collected.size

        advanceTimeBy(1_000L)
        assertEquals("no new emissions after Stop", frozen, collected.size)
        job.cancel()
    }
}
