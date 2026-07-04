package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ReaderSettingsSummariesTest {

    private val defaults = FormattingPreferences()

    @Test fun themeLabels() {
        assertEquals("Light", ReaderTheme.Light.label())
        assertEquals("Dim", ReaderTheme.DarkDim.label())
        assertEquals("Auto", ReaderTheme.Auto.label())
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

    @Test fun autoScheduleSummaryFormatsTimesAndThemes() {
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(7, 0),
            nightStart = LocalTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        assertEquals("Day 07:00 · Light → Night 21:00 · Dark", autoScheduleSummary(schedule))
    }
}
