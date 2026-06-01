package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ThemeScheduleTest {

    private val schedule = ThemeSchedule(
        dayStart = LocalTime.of(7, 0),
        nightStart = LocalTime.of(21, 0),
        dayTheme = ReaderTheme.Light,
        nightTheme = ReaderTheme.Dark,
    )

    @Test
    fun `noon is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(12, 0)))
    }

    @Test
    fun `midnight is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(0, 0)))
    }

    @Test
    fun `exactly day-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(7, 0)))
    }

    @Test
    fun `exactly night-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(21, 0)))
    }

    @Test
    fun `one minute before night-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalTime.of(20, 59)))
    }

    @Test
    fun `one minute before day-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalTime.of(6, 59)))
    }

    @Test
    fun `night arc that wraps midnight evaluates correctly past midnight`() {
        val wrap = ThemeSchedule(
            dayStart = LocalTime.of(6, 0),
            nightStart = LocalTime.of(22, 0),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalTime.of(2, 0)))
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalTime.of(23, 30)))
        assertEquals(ReaderTheme.Sepia, wrap.resolve(LocalTime.of(12, 0)))
    }

    @Test
    fun `equal day-start and night-start collapses to always-day`() {
        val degenerate = schedule.copy(
            dayStart = LocalTime.of(8, 0),
            nightStart = LocalTime.of(8, 0),
        )
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(8, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(2, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalTime.of(20, 0)))
    }

    @Test
    fun `default schedule has 07-00 21-00 Light Dark`() {
        val d = ThemeSchedule()
        assertEquals(LocalTime.of(7, 0), d.dayStart)
        assertEquals(LocalTime.of(21, 0), d.nightStart)
        assertEquals(ReaderTheme.Light, d.dayTheme)
        assertEquals(ReaderTheme.Dark, d.nightTheme)
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming day-start when currently in night`() {
        assertEquals(LocalTime.of(7, 0), schedule.nextBoundaryAfter(LocalTime.of(2, 0)))
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming night-start when currently in day`() {
        assertEquals(LocalTime.of(21, 0), schedule.nextBoundaryAfter(LocalTime.of(12, 0)))
    }

    @Test
    fun `nextBoundaryAfter at exactly the boundary returns the OTHER boundary`() {
        assertEquals(LocalTime.of(7, 0), schedule.nextBoundaryAfter(LocalTime.of(21, 0)))
    }

    @Test
    fun `nextBoundaryAfter when equal-times returns dayStart unchanged`() {
        val degenerate = schedule.copy(
            dayStart = LocalTime.of(8, 0),
            nightStart = LocalTime.of(8, 0),
        )
        assertEquals(LocalTime.of(8, 0), degenerate.nextBoundaryAfter(LocalTime.of(2, 0)))
    }
}
