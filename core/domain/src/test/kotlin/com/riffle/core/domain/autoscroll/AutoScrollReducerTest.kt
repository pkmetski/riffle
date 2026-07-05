package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollReducerTest {

    private val def = AutoScrollSpeed.of(250)

    @Test
    fun `Start from Idle enters Running at default speed`() {
        val s = reduce(AutoScrollState.Idle, AutoScrollEvent.Start, def)
        assertEquals(AutoScrollState.Running(def), s)
    }

    @Test
    fun `Start from Paused resumes Running at the retained speed`() {
        val paused = AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Start, def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), s)
    }

    @Test
    fun `Start from Running is a no-op`() {
        val running = AutoScrollState.Running(AutoScrollSpeed.of(300))
        assertEquals(running, reduce(running, AutoScrollEvent.Start, def))
    }

    @Test
    fun `Stop from any state goes to Idle`() {
        assertEquals(AutoScrollState.Idle, reduce(AutoScrollState.Idle, AutoScrollEvent.Stop, def))
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Running(def), AutoScrollEvent.Stop, def),
        )
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Paused(def, PauseCause.PanelOpen), AutoScrollEvent.Stop, def),
        )
    }

    @Test
    fun `NudgeSpeed in Running updates the speed`() {
        val s = reduce(AutoScrollState.Running(def), AutoScrollEvent.NudgeSpeed(by = 10), def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(260)), s)
    }

    @Test
    fun `NudgeSpeed in Paused updates retained speed but stays paused`() {
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.NudgeSpeed(by = -10), def)
        assertEquals(AutoScrollState.Paused(AutoScrollSpeed.of(240), PauseCause.PanelOpen), s)
    }

    @Test
    fun `NudgeSpeed in Idle is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.NudgeSpeed(by = 10), def),
        )
    }

    @Test
    fun `Pause in Running goes to Paused`() {
        val s = reduce(
            AutoScrollState.Running(def),
            AutoScrollEvent.Pause(PauseCause.PanelOpen),
            def,
        )
        assertEquals(AutoScrollState.Paused(def, PauseCause.PanelOpen), s)
    }

    @Test
    fun `Pause in Paused updates the cause to most-recent`() {
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Pause(PauseCause.ManualScroll), def)
        assertEquals(AutoScrollState.Paused(def, PauseCause.ManualScroll), s)
    }

    @Test
    fun `Pause on UserPausedPill keeps the pill park sticky`() {
        val parked = AutoScrollState.Paused(def, PauseCause.UserPausedPill)
        val s = reduce(parked, AutoScrollEvent.Pause(PauseCause.PanelOpen), def)
        assertEquals(parked, s)
    }

    @Test
    fun `Pause in Idle is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.Pause(PauseCause.PanelOpen), def),
        )
    }

    @Test
    fun `Resume from Paused goes to Running at retained speed`() {
        val paused = AutoScrollState.Paused(AutoScrollSpeed.of(300), PauseCause.PanelOpen)
        val s = reduce(paused, AutoScrollEvent.Resume, def)
        assertEquals(AutoScrollState.Running(AutoScrollSpeed.of(300)), s)
    }

    @Test
    fun `Resume from Idle is a no-op`() {
        assertEquals(AutoScrollState.Idle, reduce(AutoScrollState.Idle, AutoScrollEvent.Resume, def))
    }

    @Test
    fun `ReachedEndOfBook from Running goes silently to Idle`() {
        val s = reduce(AutoScrollState.Running(def), AutoScrollEvent.ReachedEndOfBook, def)
        assertEquals(AutoScrollState.Idle, s)
    }

    @Test
    fun `ReachedEndOfBook outside Running is a no-op`() {
        assertEquals(
            AutoScrollState.Idle,
            reduce(AutoScrollState.Idle, AutoScrollEvent.ReachedEndOfBook, def),
        )
        val paused = AutoScrollState.Paused(def, PauseCause.PanelOpen)
        assertEquals(paused, reduce(paused, AutoScrollEvent.ReachedEndOfBook, def))
    }
}
