package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import com.riffle.core.domain.LocalMinuteTime

class ThemeScheduleTest {

    private val schedule = ThemeSchedule(
        dayStart = LocalMinuteTime.of(7, 0),
        nightStart = LocalMinuteTime.of(21, 0),
        dayTheme = ReaderTheme.Light,
        nightTheme = ReaderTheme.Dark,
    )

    @Test
    fun `noon is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalMinuteTime.of(12, 0)))
    }

    @Test
    fun `midnight is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalMinuteTime.of(0, 0)))
    }

    @Test
    fun `exactly day-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalMinuteTime.of(7, 0)))
    }

    @Test
    fun `exactly night-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalMinuteTime.of(21, 0)))
    }

    @Test
    fun `one minute before night-start is day`() {
        assertEquals(ReaderTheme.Light, schedule.resolve(LocalMinuteTime.of(20, 59)))
    }

    @Test
    fun `one minute before day-start is night`() {
        assertEquals(ReaderTheme.Dark, schedule.resolve(LocalMinuteTime.of(6, 59)))
    }

    @Test
    fun `night arc that wraps midnight evaluates correctly past midnight`() {
        val wrap = ThemeSchedule(
            dayStart = LocalMinuteTime.of(6, 0),
            nightStart = LocalMinuteTime.of(22, 0),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalMinuteTime.of(2, 0)))
        assertEquals(ReaderTheme.DarkDim, wrap.resolve(LocalMinuteTime.of(23, 30)))
        assertEquals(ReaderTheme.Sepia, wrap.resolve(LocalMinuteTime.of(12, 0)))
    }

    @Test
    fun `equal day-start and night-start collapses to always-day`() {
        val degenerate = schedule.copy(
            dayStart = LocalMinuteTime.of(8, 0),
            nightStart = LocalMinuteTime.of(8, 0),
        )
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalMinuteTime.of(8, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalMinuteTime.of(2, 0)))
        assertEquals(ReaderTheme.Light, degenerate.resolve(LocalMinuteTime.of(20, 0)))
    }

    @Test
    fun `default schedule has 07-00 20-00 Light Dark`() {
        val d = ThemeSchedule()
        assertEquals(LocalMinuteTime.of(7, 0), d.dayStart)
        assertEquals(LocalMinuteTime.of(20, 0), d.nightStart)
        assertEquals(ReaderTheme.Light, d.dayTheme)
        assertEquals(ReaderTheme.Dark, d.nightTheme)
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming day-start when currently in night`() {
        assertEquals(LocalMinuteTime.of(7, 0), schedule.nextBoundaryAfter(LocalMinuteTime.of(2, 0)))
    }

    @Test
    fun `nextBoundaryAfter returns the upcoming night-start when currently in day`() {
        assertEquals(LocalMinuteTime.of(21, 0), schedule.nextBoundaryAfter(LocalMinuteTime.of(12, 0)))
    }

    @Test
    fun `nextBoundaryAfter at exactly the boundary returns the OTHER boundary`() {
        assertEquals(LocalMinuteTime.of(7, 0), schedule.nextBoundaryAfter(LocalMinuteTime.of(21, 0)))
    }

    @Test
    fun `nextBoundaryAfter when equal-times returns dayStart unchanged`() {
        val degenerate = schedule.copy(
            dayStart = LocalMinuteTime.of(8, 0),
            nightStart = LocalMinuteTime.of(8, 0),
        )
        assertEquals(LocalMinuteTime.of(8, 0), degenerate.nextBoundaryAfter(LocalMinuteTime.of(2, 0)))
    }
}
