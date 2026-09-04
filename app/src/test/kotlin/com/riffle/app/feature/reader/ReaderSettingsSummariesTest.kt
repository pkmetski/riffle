package com.riffle.app.feature.reader

import com.riffle.app.feature.readersettings.autoScheduleSummary
import com.riffle.app.feature.readersettings.autoScrollSummary
import com.riffle.app.feature.readersettings.autoThemeSummary
import com.riffle.app.feature.readersettings.behaviorSummary
import com.riffle.app.feature.readersettings.cadenceSummary
import com.riffle.app.feature.readersettings.displaySummary
import com.riffle.app.feature.readersettings.formattingSummary
import com.riffle.app.feature.readersettings.label
import com.riffle.app.feature.readersettings.lineSpacingWord
import com.riffle.app.feature.readersettings.marginsWord
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ThemeSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsSummariesTest {

    private val defaults = FormattingPreferences()

    @Test fun themeLabels() {
        assertEquals("Light", ReaderTheme.Light.label())
        assertEquals("Dim", ReaderTheme.DarkDim.label())
        assertEquals("Auto", ReaderTheme.Auto.label())
        assertEquals("Time based", AutoReaderThemeMode.Schedule.label())
        assertEquals("App theme", AutoReaderThemeMode.AppTheme.label())
    }

    @Test fun fontLabels() {
        assertEquals("Original", ReaderFontFamily.Original.label())
        assertEquals("Serif", ReaderFontFamily.Serif.label())
        assertEquals("Sans serif", ReaderFontFamily.SansSerif.label())
        assertEquals("Dyslexic", ReaderFontFamily.OpenDyslexic.label())
    }

    @Test fun lineSpacingWords() {
        assertEquals("Normal", lineSpacingWord(1.5f))
        assertEquals("Tight", lineSpacingWord(1.0f))
    }

    @Test fun marginsWords() {
        assertEquals("Normal", marginsWord(1.0f))
        assertEquals("Wide", marginsWord(3.0f))
    }

    @Test fun formattingSummaryShowsFontSizeAndMargins() {
        val prefs = defaults.copy(
            fontFamily = ReaderFontFamily.Serif,
            fontSize = 1.1f,
            margins = 1.0f,
        )
        assertEquals("Serif · 110% · Normal margins", formattingSummary(prefs))
    }

    @Test fun displaySummaryShowsThemeModeAndChapterMap() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Light,
            orientation = ReaderOrientation.Horizontal,
            showChapterMap = true,
        )
        assertEquals("Light · Paginated · map on", displaySummary(prefs))
    }

    @Test fun displaySummaryShowsAutoMode() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Auto,
            autoReaderThemeMode = AutoReaderThemeMode.AppTheme,
            orientation = ReaderOrientation.Horizontal,
            showChapterMap = true,
        )
        assertEquals("Auto App theme · Paginated · map on", displaySummary(prefs))
    }

    @Test fun displaySummaryScrollAndMapOff() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Sepia,
            orientation = ReaderOrientation.Vertical,
            showChapterMap = false,
        )
        assertEquals("Sepia · Scroll · map off", displaySummary(prefs))
    }

    @Test fun behaviorSummaryText() {
        assertEquals("Keep screen on · volume nav off", behaviorSummary(keepScreenOn = true, volumeKeyNavigationEnabled = false))
        assertEquals("Keep screen off · volume nav on", behaviorSummary(keepScreenOn = false, volumeKeyNavigationEnabled = true))
    }

    @Test fun autoScrollSummaryOnAndOff() {
        assertEquals("Hands-free scroll — 250 wpm", autoScrollSummary(defaults.copy(showAutoScroll = true, autoScrollWpm = 250)))
        assertEquals("Off", autoScrollSummary(defaults.copy(showAutoScroll = false)))
    }

    @Test fun cadenceSummaryOnAndOff() {
        assertEquals("Sentence highlight — 300 wpm", cadenceSummary(defaults.copy(showCadence = true, cadenceWpm = 300)))
        assertEquals("Off", cadenceSummary(defaults.copy(showCadence = false)))
    }

    @Test fun autoScheduleSummaryFormatsTimesAndThemes() {
        val schedule = ThemeSchedule(
            dayStart = LocalMinuteTime.of(7, 0),
            nightStart = LocalMinuteTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        assertEquals("Day 07:00 · Light → Night 21:00 · Dark", autoScheduleSummary(schedule))
    }

    @Test fun autoThemeSummaryShowsAppThemeMode() {
        val appThemeReaderThemes = AppThemeReaderThemes(
            lightTheme = ReaderTheme.Sepia,
            darkTheme = ReaderTheme.DarkDim,
        )
        assertEquals(
            "Light app · Sepia → Dark app · Dim",
            autoThemeSummary(ThemeSchedule(), AutoReaderThemeMode.AppTheme, appThemeReaderThemes),
        )
    }
}
