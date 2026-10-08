package com.riffle.core.data

import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for IosFormattingPreferencesStoreImpl: verifies default values, field
 * round-trips, the Serif/SerifV2 codec, and cross-instance persistence.
 */
class IosFormattingPreferencesStoreImplTest {

    private val allKeys = listOf(
        "formatting.font_size",
        "formatting.theme",
        "formatting.font_family",
        "formatting.line_spacing",
        "formatting.margins",
        "formatting.orientation",
        "formatting.show_chapter_map",
        "formatting.colored_chapter_map",
        "formatting.show_reading_progress_labels",
        "formatting.show_current_chapter_label",
        "formatting.show_reading_time_estimate",
        "formatting.double_page_spread",
        "formatting.justify_text",
        "formatting.auto_scroll_wpm",
        "formatting.show_auto_scroll",
        "formatting.cadence_wpm",
        "formatting.show_cadence",
        "formatting.cadence_highlight_color",
        "formatting.cadence_platform_supported",
        "formatting.auto_reader_theme_mode",
        "formatting.app_theme_light_reader_theme",
        "formatting.app_theme_dark_reader_theme",
        "formatting.theme_schedule_day_start_minute_of_day",
        "formatting.theme_schedule_night_start_minute_of_day",
        "formatting.theme_schedule_day_theme",
        "formatting.theme_schedule_night_theme",
        "formatting.force_paginated_in_landscape",
    )

    @BeforeTest
    fun clear() = allKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = allKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @Test
    fun defaultPreferencesMatchFormattingPreferencesDefaults() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        assertEquals(FormattingPreferences(), store.preferences.first())
    }

    @Test
    fun fontSizeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(fontSize = 1.5f))
        assertEquals(1.5f, store.preferences.first().fontSize)
    }

    @Test
    fun themeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(theme = ReaderTheme.Dark))
        assertEquals(ReaderTheme.Dark, store.preferences.first().theme)
    }

    @Test
    fun fontFamilyRoundtripsNonSerif() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif))
        assertEquals(ReaderFontFamily.SansSerif, store.preferences.first().fontFamily)
    }

    @Test
    fun fontFamilySerifRoundtripsAsSerif() = runTest {
        // ReaderFontFamily.Serif must persist as "SerifV2" and decode back to Serif
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertEquals(ReaderFontFamily.Serif, store.preferences.first().fontFamily)
    }

    @Test
    fun legacySerifStringDecodesToOriginal() = runTest {
        // Writing the legacy "Serif" key directly into NSUserDefaults simulates old data
        NSUserDefaults.standardUserDefaults.setObject("Serif", forKey = "formatting.font_family")
        val store = IosFormattingPreferencesStoreImpl()
        // "Serif" (without V2) decodes to Original per the codec
        assertEquals(ReaderFontFamily.Original, store.preferences.first().fontFamily)
    }

    @Test
    fun lineSpacingRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(lineSpacing = 1.8f))
        assertEquals(1.8f, store.preferences.first().lineSpacing)
    }

    @Test
    fun marginsRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(margins = 2.0f))
        assertEquals(2.0f, store.preferences.first().margins)
    }

    @Test
    fun orientationRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(orientation = ReaderOrientation.Vertical))
        assertEquals(ReaderOrientation.Vertical, store.preferences.first().orientation)
    }

    @Test
    fun showChapterMapRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showChapterMap = false))
        assertFalse(store.preferences.first().showChapterMap)
    }

    @Test
    fun coloredChapterMapRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(coloredChapterMap = false))
        assertFalse(store.preferences.first().coloredChapterMap)
    }

    @Test
    fun showReadingProgressLabelsRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showReadingProgressLabels = true))
        assertTrue(store.preferences.first().showReadingProgressLabels)
    }

    @Test
    fun showCurrentChapterLabelRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showCurrentChapterLabel = true))
        assertTrue(store.preferences.first().showCurrentChapterLabel)
    }

    @Test
    fun showReadingTimeEstimateRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showReadingTimeEstimate = true))
        assertTrue(store.preferences.first().showReadingTimeEstimate)
    }

    @Test
    fun doublePageSpreadRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(doublePageSpread = true))
        assertTrue(store.preferences.first().doublePageSpread)
    }

    @Test
    fun justifyTextRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(justifyText = true))
        assertTrue(store.preferences.first().justifyText)
    }

    @Test
    fun autoScrollWpmRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(autoScrollWpm = 300))
        assertEquals(300, store.preferences.first().autoScrollWpm)
    }

    @Test
    fun showAutoScrollRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showAutoScroll = true))
        assertTrue(store.preferences.first().showAutoScroll)
    }

    @Test
    fun cadenceWpmRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(cadenceWpm = 400))
        assertEquals(400, store.preferences.first().cadenceWpm)
    }

    @Test
    fun showCadenceRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(showCadence = true))
        assertTrue(store.preferences.first().showCadence)
    }

    @Test
    fun cadenceHighlightColorRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(cadenceHighlightColor = HighlightColor.GREEN))
        assertEquals(HighlightColor.GREEN, store.preferences.first().cadenceHighlightColor)
    }

    @Test
    fun autoReaderThemeModeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(autoReaderThemeMode = AutoReaderThemeMode.AppTheme))
        assertEquals(AutoReaderThemeMode.AppTheme, store.preferences.first().autoReaderThemeMode)
    }

    @Test
    fun appThemeReaderThemesRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        val themes = AppThemeReaderThemes(
            lightTheme = ReaderTheme.Sepia,
            darkTheme = ReaderTheme.Dark,
        )
        store.update(FormattingPreferences(appThemeReaderThemes = themes))
        assertEquals(themes, store.preferences.first().appThemeReaderThemes)
    }

    @Test
    fun themeScheduleDayStartRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        val schedule = ThemeSchedule(dayStart = LocalMinuteTime.of(8, 30))
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(LocalMinuteTime.of(8, 30), store.preferences.first().themeSchedule.dayStart)
    }

    @Test
    fun themeScheduleNightStartRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        val schedule = ThemeSchedule(nightStart = LocalMinuteTime.of(22, 0))
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(LocalMinuteTime.of(22, 0), store.preferences.first().themeSchedule.nightStart)
    }

    @Test
    fun themeScheduleDayThemeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        val schedule = ThemeSchedule(dayTheme = ReaderTheme.Sepia)
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(ReaderTheme.Sepia, store.preferences.first().themeSchedule.dayTheme)
    }

    @Test
    fun themeScheduleNightThemeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        val schedule = ThemeSchedule(nightTheme = ReaderTheme.Dark)
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(ReaderTheme.Dark, store.preferences.first().themeSchedule.nightTheme)
    }

    @Test
    fun forcePaginatedInLandscapeRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.update(FormattingPreferences(forcePaginatedInLandscape = true))
        assertTrue(store.preferences.first().forcePaginatedInLandscape)
    }

    @Test
    fun valuesPersistAcrossStoreInstances() = runTest {
        IosFormattingPreferencesStoreImpl().update(
            FormattingPreferences(
                fontSize = 1.4f,
                theme = ReaderTheme.Dark,
                fontFamily = ReaderFontFamily.Monospace,
                lineSpacing = 1.6f,
                margins = 1.5f,
                justifyText = true,
                autoScrollWpm = 350,
                forcePaginatedInLandscape = true,
            ),
        )

        val reopened = IosFormattingPreferencesStoreImpl()
        val prefs = reopened.preferences.first()
        assertEquals(1.4f, prefs.fontSize)
        assertEquals(ReaderTheme.Dark, prefs.theme)
        assertEquals(ReaderFontFamily.Monospace, prefs.fontFamily)
        assertEquals(1.6f, prefs.lineSpacing)
        assertEquals(1.5f, prefs.margins)
        assertTrue(prefs.justifyText)
        assertEquals(350, prefs.autoScrollWpm)
        assertTrue(prefs.forcePaginatedInLandscape)
    }

    @Test
    fun setCadencePlatformSupportedRoundtrips() = runTest {
        val store = IosFormattingPreferencesStoreImpl()
        store.setCadencePlatformSupported(false)
        assertFalse(store.preferences.first().cadencePlatformSupported)
    }
}
