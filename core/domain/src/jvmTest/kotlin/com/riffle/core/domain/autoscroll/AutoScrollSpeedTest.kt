package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoScrollSpeedTest {

    @Test
    fun `default is 250 wpm`() {
        assertEquals(250, AutoScrollSpeed.Default.wpm)
    }

    @Test
    fun `of clamps below MIN`() {
        assertEquals(80, AutoScrollSpeed.of(50).wpm)
        assertEquals(80, AutoScrollSpeed.of(0).wpm)
        assertEquals(80, AutoScrollSpeed.of(-100).wpm)
    }

    @Test
    fun `of clamps above MAX`() {
        assertEquals(600, AutoScrollSpeed.of(700).wpm)
        assertEquals(600, AutoScrollSpeed.of(Int.MAX_VALUE).wpm)
    }

    @Test
    fun `of snaps to STEP grid`() {
        assertEquals(250, AutoScrollSpeed.of(254).wpm)
        assertEquals(260, AutoScrollSpeed.of(255).wpm)
        assertEquals(250, AutoScrollSpeed.of(251).wpm)
    }

    @Test
    fun `nudge adds the step and clamps`() {
        val s = AutoScrollSpeed.of(250)
        assertEquals(260, s.nudge(10).wpm)
        assertEquals(240, s.nudge(-10).wpm)
        assertEquals(280, s.nudge(30).wpm)
    }

    @Test
    fun `nudge clamps at MAX`() {
        val s = AutoScrollSpeed.of(590)
        assertEquals(600, s.nudge(10).wpm)
        assertEquals(600, s.nudge(50).wpm)
    }

    @Test
    fun `nudge clamps at MIN`() {
        val s = AutoScrollSpeed.of(90)
        assertEquals(80, s.nudge(-10).wpm)
        assertEquals(80, s.nudge(-50).wpm)
    }

    @Test
    fun `nudge by non-step amount snaps to grid`() {
        val s = AutoScrollSpeed.of(250)
        // nudging by 13 lands at 263 → snap to 260
        assertEquals(260, s.nudge(13).wpm)
    }

    @Test
    fun `constants match ADR 0037`() {
        assertEquals(80, AutoScrollSpeed.MIN_WPM)
        assertEquals(600, AutoScrollSpeed.MAX_WPM)
        assertEquals(10, AutoScrollSpeed.STEP_WPM)
        assertEquals(250, AutoScrollSpeed.DEFAULT_WPM)
    }
}
