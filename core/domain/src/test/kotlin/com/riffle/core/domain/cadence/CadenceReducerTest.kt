package com.riffle.core.domain.cadence

import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceReducerTest {
    private val defaultSpeed = AutoScrollSpeed.of(250)

    @Test
    fun `Start from Idle goes Running at default speed`() {
        val out = reduce(CadenceState.Idle, CadenceEvent.Start, defaultSpeed)
        assertEquals(CadenceState.Running(defaultSpeed), out)
    }

    @Test
    fun `Start while Running is a no-op`() {
        val running = CadenceState.Running(defaultSpeed)
        assertSame(running, reduce(running, CadenceEvent.Start, defaultSpeed))
    }

    @Test
    fun `Start from Paused resumes at the same speed the pause captured`() {
        val paused = CadenceState.Paused(AutoScrollSpeed.of(400), PauseCause.PanelOpen)
        val out = reduce(paused, CadenceEvent.Start, defaultSpeed)
        assertEquals(CadenceState.Running(AutoScrollSpeed.of(400)), out)
    }

    @Test
    fun `Stop from anywhere goes Idle`() {
        assertEquals(CadenceState.Idle, reduce(CadenceState.Running(defaultSpeed), CadenceEvent.Stop, defaultSpeed))
        assertEquals(
            CadenceState.Idle,
            reduce(CadenceState.Paused(defaultSpeed, PauseCause.ScreenOff), CadenceEvent.Stop, defaultSpeed),
        )
    }

    @Test
    fun `NudgeSpeed while Running scales the live speed`() {
        val running = CadenceState.Running(AutoScrollSpeed.of(200))
        val out = reduce(running, CadenceEvent.NudgeSpeed(50), defaultSpeed)
        assertEquals(CadenceState.Running(AutoScrollSpeed.of(250)), out)
    }

    @Test
    fun `NudgeSpeed while Paused adjusts the resume speed`() {
        val paused = CadenceState.Paused(AutoScrollSpeed.of(200), PauseCause.PanelOpen)
        val out = reduce(paused, CadenceEvent.NudgeSpeed(50), defaultSpeed)
        assertEquals(CadenceState.Paused(AutoScrollSpeed.of(250), PauseCause.PanelOpen), out)
    }

    @Test
    fun `NudgeSpeed while Idle is a no-op`() {
        assertSame(CadenceState.Idle, reduce(CadenceState.Idle, CadenceEvent.NudgeSpeed(50), defaultSpeed))
    }

    @Test
    fun `Pause while Running captures the pause cause and keeps the speed`() {
        val out = reduce(
            CadenceState.Running(AutoScrollSpeed.of(320)),
            CadenceEvent.Pause(PauseCause.ReadaloudStarted),
            defaultSpeed,
        )
        assertEquals(CadenceState.Paused(AutoScrollSpeed.of(320), PauseCause.ReadaloudStarted), out)
    }

    @Test
    fun `Pause while already Paused updates the cause`() {
        val paused = CadenceState.Paused(defaultSpeed, PauseCause.PanelOpen)
        val out = reduce(paused, CadenceEvent.Pause(PauseCause.AppBackgrounded), defaultSpeed)
        assertEquals(CadenceState.Paused(defaultSpeed, PauseCause.AppBackgrounded), out)
    }

    @Test
    fun `Resume from Paused goes Running at the paused speed`() {
        val out = reduce(
            CadenceState.Paused(AutoScrollSpeed.of(320), PauseCause.PanelOpen),
            CadenceEvent.Resume,
            defaultSpeed,
        )
        assertEquals(CadenceState.Running(AutoScrollSpeed.of(320)), out)
    }

    @Test
    fun `Resume from Idle is a no-op`() {
        assertSame(CadenceState.Idle, reduce(CadenceState.Idle, CadenceEvent.Resume, defaultSpeed))
    }

    @Test
    fun `ReachedEndOfBook stops Cadence`() {
        val out = reduce(CadenceState.Running(defaultSpeed), CadenceEvent.ReachedEndOfBook, defaultSpeed)
        assertEquals(CadenceState.Idle, out)
    }

    @Test
    fun `ReadaloudStarted pause cause is representable`() {
        // Regression: the mutual-exclusion arbiter fans out `Pause(ReadaloudStarted)` when
        // Readaloud starts while Cadence is running. If someone renamed the enum entry the
        // arbiter's call site would fail to compile — this test pins the entry name.
        val out = reduce(
            CadenceState.Running(defaultSpeed),
            CadenceEvent.Pause(PauseCause.ReadaloudStarted),
            defaultSpeed,
        )
        assertTrue(out is CadenceState.Paused && out.cause == PauseCause.ReadaloudStarted)
    }

    @Test
    fun `AutoScrollStarted pause cause is representable`() {
        val out = reduce(
            CadenceState.Running(defaultSpeed),
            CadenceEvent.Pause(PauseCause.AutoScrollStarted),
            defaultSpeed,
        )
        assertTrue(out is CadenceState.Paused && out.cause == PauseCause.AutoScrollStarted)
    }

    @Test
    fun `isActive is true only for Running`() {
        assertTrue(CadenceState.Running(defaultSpeed).isActive)
        assertTrue(!CadenceState.Idle.isActive)
        assertTrue(!CadenceState.Paused(defaultSpeed, PauseCause.PanelOpen).isActive)
    }
}
