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

class IosFormattingPreferencesStoreImplTest {

    private val allKeys = listOf(
        "font_size",
        "theme",
        "font_family",
        "line_spacing",
        "margins",
        "orientation",
        "show_chapter_map",
        "colored_chapter_map",
        "show_reading_progress_labels",
        "show_current_chapter_label",
        "show_reading_time_estimate",
        "double_page_spread",
        "justify_text",
        "auto_scroll_wpm",
        "show_auto_scroll",
        "cadence_wpm",
        "show_cadence",
        "cadence_highlight_color",
        "cadence_platform_supported",
        "auto_reader_theme_mode",
        "app_theme_light_reader_theme",
        "app_theme_dark_reader_theme",
        "theme_schedule_day_start_minute_of_day",
        "theme_schedule_night_start_minute_of_day",
        "theme_schedule_day_theme",
        "theme_schedule_night_theme",
        "force_paginated_in_landscape",
    )

    @BeforeTest
    fun clear() = allKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    @AfterTest
    fun cleanup() = allKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }

    private fun buildStore() = FormattingPreferencesStoreImpl(IosKeyValueStore())

    @Test
    fun defaultPreferencesMatchFormattingPreferencesDefaults() = runTest {
        assertEquals(FormattingPreferences(), buildStore().preferences.first())
    }

    @Test
    fun fontSizeRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontSize = 1.5f))
        assertEquals(1.5f, store.preferences.first().fontSize)
    }

    @Test
    fun themeRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(theme = ReaderTheme.Dark))
        assertEquals(ReaderTheme.Dark, store.preferences.first().theme)
    }

    @Test
    fun fontFamilyRoundtripsNonSerif() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif))
        assertEquals(ReaderFontFamily.SansSerif, store.preferences.first().fontFamily)
    }

    @Test
    fun fontFamilySerifRoundtripsAsSerif() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertEquals(ReaderFontFamily.Serif, store.preferences.first().fontFamily)
    }

    @Test
    fun legacySerifStringDecodesToOriginal() = runTest {
        NSUserDefaults.standardUserDefaults.setObject("Serif", forKey = "font_family")
        val store = buildStore()
        assertEquals(ReaderFontFamily.Original, store.preferences.first().fontFamily)
    }

    @Test
    fun lineSpacingRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(lineSpacing = 1.8f))
        assertEquals(1.8f, store.preferences.first().lineSpacing)
    }

    @Test
    fun marginsRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(margins = 2.0f))
        assertEquals(2.0f, store.preferences.first().margins)
    }

    @Test
    fun orientationRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(orientation = ReaderOrientation.Vertical))
        assertEquals(ReaderOrientation.Vertical, store.preferences.first().orientation)
    }

    @Test
    fun showChapterMapRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showChapterMap = false))
        assertFalse(store.preferences.first().showChapterMap)
    }

    @Test
    fun coloredChapterMapRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(coloredChapterMap = false))
        assertFalse(store.preferences.first().coloredChapterMap)
    }

    @Test
    fun showReadingProgressLabelsRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showReadingProgressLabels = true))
        assertTrue(store.preferences.first().showReadingProgressLabels)
    }

    @Test
    fun showCurrentChapterLabelRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showCurrentChapterLabel = true))
        assertTrue(store.preferences.first().showCurrentChapterLabel)
    }

    @Test
    fun showReadingTimeEstimateRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showReadingTimeEstimate = true))
        assertTrue(store.preferences.first().showReadingTimeEstimate)
    }

    @Test
    fun doublePageSpreadRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(doublePageSpread = true))
        assertTrue(store.preferences.first().doublePageSpread)
    }

    @Test
    fun justifyTextRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(justifyText = true))
        assertTrue(store.preferences.first().justifyText)
    }

    @Test
    fun autoScrollWpmRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(autoScrollWpm = 300))
        assertEquals(300, store.preferences.first().autoScrollWpm)
    }

    @Test
    fun showAutoScrollRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showAutoScroll = true))
        assertTrue(store.preferences.first().showAutoScroll)
    }

    @Test
    fun cadenceWpmRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(cadenceWpm = 400))
        assertEquals(400, store.preferences.first().cadenceWpm)
    }

    @Test
    fun showCadenceRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showCadence = true))
        assertTrue(store.preferences.first().showCadence)
    }

    @Test
    fun cadenceHighlightColorRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(cadenceHighlightColor = HighlightColor.GREEN))
        assertEquals(HighlightColor.GREEN, store.preferences.first().cadenceHighlightColor)
    }

    @Test
    fun autoReaderThemeModeRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(autoReaderThemeMode = AutoReaderThemeMode.AppTheme))
        assertEquals(AutoReaderThemeMode.AppTheme, store.preferences.first().autoReaderThemeMode)
    }

    @Test
    fun appThemeReaderThemesRoundtrips() = runTest {
        val store = buildStore()
        val themes = AppThemeReaderThemes(
            lightTheme = ReaderTheme.Sepia,
            darkTheme = ReaderTheme.Dark,
        )
        store.update(FormattingPreferences(appThemeReaderThemes = themes))
        assertEquals(themes, store.preferences.first().appThemeReaderThemes)
    }

    @Test
    fun themeScheduleDayStartRoundtrips() = runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(dayStart = LocalMinuteTime.of(8, 30))
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(LocalMinuteTime.of(8, 30), store.preferences.first().themeSchedule.dayStart)
    }

    @Test
    fun themeScheduleNightStartRoundtrips() = runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(nightStart = LocalMinuteTime.of(22, 0))
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(LocalMinuteTime.of(22, 0), store.preferences.first().themeSchedule.nightStart)
    }

    @Test
    fun themeScheduleDayThemeRoundtrips() = runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(dayTheme = ReaderTheme.Sepia)
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(ReaderTheme.Sepia, store.preferences.first().themeSchedule.dayTheme)
    }

    @Test
    fun themeScheduleNightThemeRoundtrips() = runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(nightTheme = ReaderTheme.Dark)
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(ReaderTheme.Dark, store.preferences.first().themeSchedule.nightTheme)
    }

    @Test
    fun forcePaginatedInLandscapeRoundtrips() = runTest {
        val store = buildStore()
        store.update(FormattingPreferences(forcePaginatedInLandscape = true))
        assertTrue(store.preferences.first().forcePaginatedInLandscape)
    }

    @Test
    fun valuesPersistAcrossStoreInstances() = runTest {
        buildStore().update(
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

        val reopened = buildStore()
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
        val store = buildStore()
        store.setCadencePlatformSupported(false)
        assertFalse(store.preferences.first().cadencePlatformSupported)
    }
}
