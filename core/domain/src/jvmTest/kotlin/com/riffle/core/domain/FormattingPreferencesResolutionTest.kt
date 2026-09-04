package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import com.riffle.core.domain.LocalMinuteTime

class FormattingPreferencesResolutionTest {

    @Test
    fun `concrete theme is unchanged by withResolvedTheme`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Sepia)
        assertEquals(prefs, prefs.withResolvedTheme(LocalMinuteTime.of(12, 0)))
    }

    @Test
    fun `Auto resolves to day theme during day`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Light, prefs.withResolvedTheme(LocalMinuteTime.of(12, 0)).theme)
    }

    @Test
    fun `Auto resolves to night theme during night`() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)
        assertEquals(ReaderTheme.Dark, prefs.withResolvedTheme(LocalMinuteTime.of(22, 0)).theme)
    }

    @Test
    fun `Auto with a custom schedule picks the custom night theme`() {
        val prefs = FormattingPreferences(
            theme = ReaderTheme.Auto,
            autoReaderThemeMode = AutoReaderThemeMode.Schedule,
            themeSchedule = ThemeSchedule(
                dayStart = LocalMinuteTime.of(7, 0),
                nightStart = LocalMinuteTime.of(21, 0),
                dayTheme = ReaderTheme.Sepia,
                nightTheme = ReaderTheme.DarkDim,
            ),
        )
        assertEquals(ReaderTheme.DarkDim, prefs.withResolvedTheme(LocalMinuteTime.of(22, 0)).theme)
        assertEquals(ReaderTheme.Sepia, prefs.withResolvedTheme(LocalMinuteTime.of(12, 0)).theme)
    }

    @Test
    fun `default themeSchedule is the ThemeSchedule default`() {
        assertEquals(ThemeSchedule(), FormattingPreferences().themeSchedule)
    }

    @Test
    fun `Auto in app-theme mode follows forced app Dark`() {
        val prefs = FormattingPreferences(
            theme = ReaderTheme.Auto,
            autoReaderThemeMode = AutoReaderThemeMode.AppTheme,
            appThemeReaderThemes = AppThemeReaderThemes(
                lightTheme = ReaderTheme.Sepia,
                darkTheme = ReaderTheme.DarkDim,
            ),
        )

        assertEquals(
            ReaderTheme.DarkDim,
            prefs.withResolvedTheme(
                now = LocalMinuteTime.of(12, 0),
                appTheme = AppTheme.Dark,
                systemInDark = false,
            ).theme,
        )
    }

    @Test
    fun `Auto in app-theme mode follows System app theme from OS flag`() {
        val prefs = FormattingPreferences(
            theme = ReaderTheme.Auto,
            autoReaderThemeMode = AutoReaderThemeMode.AppTheme,
            appThemeReaderThemes = AppThemeReaderThemes(
                lightTheme = ReaderTheme.Sepia,
                darkTheme = ReaderTheme.DarkDim,
            ),
        )

        assertEquals(
            ReaderTheme.DarkDim,
            prefs.withResolvedTheme(
                now = LocalMinuteTime.of(12, 0),
                appTheme = AppTheme.System,
                systemInDark = true,
            ).theme,
        )
        assertEquals(
            ReaderTheme.Sepia,
            prefs.withResolvedTheme(
                now = LocalMinuteTime.of(12, 0),
                appTheme = AppTheme.System,
                systemInDark = false,
            ).theme,
        )
    }
}
