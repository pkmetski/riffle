package com.riffle.feature.settings

import com.riffle.feature.settings.label
import com.riffle.feature.settings.label
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ThemeSchedule
import kotlin.test.assertEquals
import kotlin.test.Test

class ReaderSettingsSummariesTest {

    private val defaults = FormattingPreferences()

    @Test fun themeLabels() {
        assertEquals("Light", ReaderTheme.Light.label())
        assertEquals("Dim", ReaderTheme.DarkDim.label())
        assertEquals("Auto", ReaderTheme.Auto.label())
        assertEquals("Time based", ReaderSettingsSummaries.autoModeLabel(AutoReaderThemeMode.Schedule))
        assertEquals("App theme", ReaderSettingsSummaries.autoModeLabel(AutoReaderThemeMode.AppTheme))
    }

    @Test fun fontLabels() {
        assertEquals("Original", ReaderSettingsSummaries.fontFamilyLabel(ReaderFontFamily.Original))
        assertEquals("Serif", ReaderSettingsSummaries.fontFamilyLabel(ReaderFontFamily.Serif))
        assertEquals("Sans serif", ReaderSettingsSummaries.fontFamilyLabel(ReaderFontFamily.SansSerif))
        assertEquals("Dyslexic", ReaderSettingsSummaries.fontFamilyLabel(ReaderFontFamily.OpenDyslexic))
    }

    @Test fun lineSpacingWords() {
        assertEquals("Normal", ReaderSettingsSummaries.lineSpacingWord(1.5f))
        assertEquals("Tight", ReaderSettingsSummaries.lineSpacingWord(1.0f))
    }

    @Test fun marginsWords() {
        assertEquals("Normal", ReaderSettingsSummaries.marginsWord(1.0f))
        assertEquals("Wide", ReaderSettingsSummaries.marginsWord(3.0f))
    }

    /**
     * The font percentage rounds, it does not truncate. iOS's Formatting stepper used `toInt()`
     * while the summary row one screen up went through [ReaderSettingsSummaries.formattingSummary]
     * and rounded, so a 1.149 scale read 115% in the summary and 114% in the stepper below it.
     * 1.149f is chosen because the two differ there; on revert this assertion reads "114%".
     */
    @Test fun fontSizePercentRoundsRatherThanTruncates() {
        assertEquals("115%", ReaderSettingsSummaries.fontSizePercentLabel(1.149f))
        assertEquals("100%", ReaderSettingsSummaries.fontSizePercentLabel(1.0f))
        assertEquals("250%", ReaderSettingsSummaries.fontSizePercentLabel(2.5f))
    }

    /** The stepper caption and the summary row must print the same number for the same scale. */
    @Test fun fontSizeStepperAndSummaryAgreeOnEveryTenthOfAScale() {
        for (hundredths in 50..300) {
            val scale = hundredths / 100f
            val prefs = defaults.copy(fontFamily = ReaderFontFamily.Serif, fontSize = scale, margins = 1.0f)
            val fromStepper = ReaderSettingsSummaries.fontSizePercentLabel(scale)
            val fromSummary = ReaderSettingsSummaries.formattingSummary(prefs).split(" · ")[1]
            assertEquals(fromSummary, fromStepper, "font-size percent disagrees at scale $scale")
        }
    }

    @Test fun scaleTimesLabelPrintsOneDecimal() {
        assertEquals("1.5×", ReaderSettingsSummaries.scaleTimesLabel(1.5f))
        assertEquals("1.0×", ReaderSettingsSummaries.scaleTimesLabel(1.0f))
        assertEquals("2.4×", ReaderSettingsSummaries.scaleTimesLabel(2.35f))
    }

    @Test fun lineSpacingAndMarginsCaptionsPairTheWordWithTheMultiplier() {
        assertEquals("Normal · 1.5×", ReaderSettingsSummaries.lineSpacingCaption(1.5f))
        assertEquals("Normal · 1.0×", ReaderSettingsSummaries.marginsCaption(1.0f))
        assertEquals("Edge · 0.2×", ReaderSettingsSummaries.marginsCaption(0.2f))
    }

    @Test fun formattingSummaryShowsFontSizeAndMargins() {
        val prefs = defaults.copy(
            fontFamily = ReaderFontFamily.Serif,
            fontSize = 1.1f,
            margins = 1.0f,
        )
        assertEquals("Serif · 110% · Normal margins", ReaderSettingsSummaries.formattingSummary(prefs))
    }

    @Test fun displaySummaryShowsThemeModeAndChapterMap() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Light,
            orientation = ReaderOrientation.Horizontal,
            showChapterMap = true,
        )
        assertEquals("Light · Paginated · map on", ReaderSettingsSummaries.displaySummary(prefs))
    }

    @Test fun displaySummaryShowsAutoMode() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Auto,
            autoReaderThemeMode = AutoReaderThemeMode.AppTheme,
            orientation = ReaderOrientation.Horizontal,
            showChapterMap = true,
        )
        assertEquals("Auto App theme · Paginated · map on", ReaderSettingsSummaries.displaySummary(prefs))
    }

    @Test fun displaySummaryScrollAndMapOff() {
        val prefs = defaults.copy(
            theme = ReaderTheme.Sepia,
            orientation = ReaderOrientation.Vertical,
            showChapterMap = false,
        )
        assertEquals("Sepia · Scroll · map off", ReaderSettingsSummaries.displaySummary(prefs))
    }

    @Test fun behaviorSummaryText() {
        assertEquals("Keep screen on · volume nav off", ReaderSettingsSummaries.behaviorSummary(keepScreenOn = true, volumeKeyNavigationEnabled = false))
        assertEquals("Keep screen off · volume nav on", ReaderSettingsSummaries.behaviorSummary(keepScreenOn = false, volumeKeyNavigationEnabled = true))
    }

    @Test fun autoScrollSummaryOnAndOff() {
        assertEquals("Hands-free scroll — 250 wpm", ReaderSettingsSummaries.autoScrollSummary(defaults.copy(showAutoScroll = true, autoScrollWpm = 250)))
        assertEquals("Off", ReaderSettingsSummaries.autoScrollSummary(defaults.copy(showAutoScroll = false)))
    }

    @Test fun cadenceSummaryOnAndOff() {
        assertEquals("Sentence highlight — 300 wpm", ReaderSettingsSummaries.cadenceSummary(defaults.copy(showCadence = true, cadenceWpm = 300)))
        assertEquals("Off", ReaderSettingsSummaries.cadenceSummary(defaults.copy(showCadence = false)))
    }

    @Test fun autoScheduleSummaryFormatsTimesAndThemes() {
        val schedule = ThemeSchedule(
            dayStart = LocalMinuteTime.of(7, 0),
            nightStart = LocalMinuteTime.of(21, 0),
            dayTheme = ReaderTheme.Light,
            nightTheme = ReaderTheme.Dark,
        )
        assertEquals("Day 07:00 · Light → Night 21:00 · Dark", ReaderSettingsSummaries.autoScheduleSummary(schedule))
    }

    @Test fun autoThemeSummaryShowsAppThemeMode() {
        val appThemeReaderThemes = AppThemeReaderThemes(
            lightTheme = ReaderTheme.Sepia,
            darkTheme = ReaderTheme.DarkDim,
        )
        assertEquals(
            "Light app · Sepia → Dark app · Dim",
            ReaderSettingsSummaries.autoThemeSummary(ThemeSchedule(), AutoReaderThemeMode.AppTheme, appThemeReaderThemes),
        )
    }
}
