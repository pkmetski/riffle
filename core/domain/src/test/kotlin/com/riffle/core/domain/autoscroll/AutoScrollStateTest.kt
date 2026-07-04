package com.riffle.core.domain.autoscroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollStateTest {

    @Test
    fun `Idle is not active and has no speed`() {
        val s: AutoScrollState = AutoScrollState.Idle
        assertFalse(s.isActive)
        assertNull(s.speedOrNull)
    }

    @Test
    fun `Running is active and exposes speed`() {
        val s: AutoScrollState = AutoScrollState.Running(AutoScrollSpeed.Default)
        assertTrue(s.isActive)
        assertEquals(AutoScrollSpeed.Default, s.speedOrNull)
    }

    @Test
    fun `Paused is not active but retains speed`() {
        val speed = AutoScrollSpeed.of(300)
        val s: AutoScrollState = AutoScrollState.Paused(speed, PauseCause.PanelOpen)
        assertFalse(s.isActive)
        assertEquals(speed, s.speedOrNull)
    }

    @Test
    fun `every PauseCause exists`() {
        val all = PauseCause.values().toSet()
        assertTrue(PauseCause.AppBackgrounded in all)
        assertTrue(PauseCause.ScreenOff in all)
        assertTrue(PauseCause.ManualScroll in all)
        assertTrue(PauseCause.TextSelection in all)
        assertTrue(PauseCause.OrientationChange in all)
        assertTrue(PauseCause.PanelOpen in all)
        assertTrue(PauseCause.ReadaloudStarted in all)
        assertTrue(PauseCause.UserPausedPill in all)
        assertEquals(8, all.size)
    }

    @Test
    fun `HUD pill is visible while Running`() {
        assertTrue(AutoScrollState.Running(AutoScrollSpeed.Default).isHudPillVisible)
    }

    @Test
    fun `HUD pill is visible when the user paused it from the pill`() {
        val s: AutoScrollState = AutoScrollState.Paused(AutoScrollSpeed.Default, PauseCause.UserPausedPill)
        assertTrue(s.isHudPillVisible)
    }

    @Test
    fun `HUD pill is hidden for system-driven pause causes`() {
        val systemCauses = PauseCause.values().filter { it != PauseCause.UserPausedPill }
        for (cause in systemCauses) {
            val s: AutoScrollState = AutoScrollState.Paused(AutoScrollSpeed.Default, cause)
            assertFalse("pill must hide for $cause", s.isHudPillVisible)
        }
    }

    @Test
    fun `HUD pill is hidden when Idle`() {
        assertFalse(AutoScrollState.Idle.isHudPillVisible)
    }
}
