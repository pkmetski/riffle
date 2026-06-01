package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FormattingPreferencesResolutionTest {

    @Test
    fun `concrete theme is unchanged by withResolvedTheme`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Sepia)
        assertEquals(prefs, prefs.withResolvedTheme(LocalTime.of(12, 0)))
    }

    @Test
    fun `Auto resolves to day theme during day`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Light, prefs.withResolvedTheme(LocalTime.of(12, 0)).theme)
    }

    @Test
    fun `Auto resolves to night theme during night`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Dark, prefs.withResolvedTheme(LocalTime.of(22, 0)).theme)
    }

    @Test
    fun `Auto with a custom schedule picks the custom night theme`() {
        val prefs = FormattingPreferences(
            theme = ReaderTheme.Auto,
            themeSchedule = ThemeSchedule(
                dayStart = LocalTime.of(7, 0),
                nightStart = LocalTime.of(21, 0),
                dayTheme = ReaderTheme.Sepia,
                nightTheme = ReaderTheme.DarkDim,
            ),
        )
        assertEquals(ReaderTheme.DarkDim, prefs.withResolvedTheme(LocalTime.of(22, 0)).theme)
        assertEquals(ReaderTheme.Sepia, prefs.withResolvedTheme(LocalTime.of(12, 0)).theme)
    }

    @Test
    fun `default themeSchedule is the ThemeSchedule default`() {
        assertEquals(ThemeSchedule(), FormattingPreferences().themeSchedule)
    }
}
