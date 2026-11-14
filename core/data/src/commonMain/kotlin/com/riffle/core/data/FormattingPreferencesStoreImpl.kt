package com.riffle.core.data

import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LocalMinuteTime
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.Flow

class FormattingPreferencesStoreImpl(private val store: KeyValueStore) : FormattingPreferencesStore {

    override val preferences: Flow<FormattingPreferences> = store.observe { load() }

    override suspend fun update(preferences: FormattingPreferences) {
        store.setFloat(KEY_FONT_SIZE, preferences.fontSize)
        store.setString(KEY_THEME, preferences.theme.name)
        store.setString(KEY_FONT_FAMILY, preferences.fontFamily.encodePersistName())
        store.setFloat(KEY_LINE_SPACING, preferences.lineSpacing)
        store.setFloat(KEY_MARGINS, preferences.margins)
        store.setString(KEY_ORIENTATION, preferences.orientation.name)
        store.setBoolean(KEY_SHOW_CHAPTER_MAP, preferences.showChapterMap)
        store.setBoolean(KEY_COLORED_CHAPTER_MAP, preferences.coloredChapterMap)
        store.setBoolean(KEY_SHOW_READING_PROGRESS_LABELS, preferences.showReadingProgressLabels)
        store.setBoolean(KEY_SHOW_CURRENT_CHAPTER_LABEL, preferences.showCurrentChapterLabel)
        store.setBoolean(KEY_SHOW_READING_TIME_ESTIMATE, preferences.showReadingTimeEstimate)
        store.setBoolean(KEY_DOUBLE_PAGE_SPREAD, preferences.doublePageSpread)
        store.setBoolean(KEY_FORCE_PAGINATED_IN_LANDSCAPE, preferences.forcePaginatedInLandscape)
        store.setBoolean(KEY_JUSTIFY_TEXT, preferences.justifyText)
        store.setInt(KEY_AUTO_SCROLL_WPM, preferences.autoScrollWpm)
        store.setBoolean(KEY_SHOW_AUTO_SCROLL, preferences.showAutoScroll)
        store.setInt(KEY_CADENCE_WPM, preferences.cadenceWpm)
        store.setBoolean(KEY_SHOW_CADENCE, preferences.showCadence)
        store.setString(KEY_CADENCE_HIGHLIGHT_COLOR, preferences.cadenceHighlightColor.name)
        store.setString(KEY_AUTO_READER_THEME_MODE, preferences.autoReaderThemeMode.name)
        store.setString(KEY_APP_THEME_LIGHT_READER_THEME, preferences.appThemeReaderThemes.lightTheme.name)
        store.setString(KEY_APP_THEME_DARK_READER_THEME, preferences.appThemeReaderThemes.darkTheme.name)
        store.setInt(KEY_SCHEDULE_DAY_START, preferences.themeSchedule.dayStart.toMinuteOfDay())
        store.setInt(KEY_SCHEDULE_NIGHT_START, preferences.themeSchedule.nightStart.toMinuteOfDay())
        store.setString(KEY_SCHEDULE_DAY_THEME, preferences.themeSchedule.dayTheme.name)
        store.setString(KEY_SCHEDULE_NIGHT_THEME, preferences.themeSchedule.nightTheme.name)
        // KEY_CADENCE_PLATFORM_SUPPORTED is intentionally NOT written here — only
        // setCadencePlatformSupported() may touch it, so a concurrent user-driven preferences
        // write cannot overwrite the reader's WebView feature-detect result.
    }

    override suspend fun setCadencePlatformSupported(supported: Boolean) =
        store.setBoolean(KEY_CADENCE_PLATFORM_SUPPORTED, supported)

    private suspend fun load(): FormattingPreferences = FormattingPreferences(
        fontSize = store.getFloat(KEY_FONT_SIZE, FormattingPreferences.DEFAULT_FONT_SIZE),
        theme = store.getString(KEY_THEME)
            ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_THEME,
        fontFamily = store.getString(KEY_FONT_FAMILY)?.decodeFontFamily()
            ?: FormattingPreferences.DEFAULT_FONT_FAMILY,
        lineSpacing = store.getFloat(KEY_LINE_SPACING, FormattingPreferences.DEFAULT_LINE_SPACING),
        margins = store.getFloat(KEY_MARGINS, FormattingPreferences.DEFAULT_MARGINS),
        orientation = store.getString(KEY_ORIENTATION)
            ?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_ORIENTATION,
        showChapterMap = store.getBoolean(KEY_SHOW_CHAPTER_MAP, FormattingPreferences.DEFAULT_SHOW_CHAPTER_MAP),
        coloredChapterMap = store.getBoolean(KEY_COLORED_CHAPTER_MAP, FormattingPreferences.DEFAULT_COLORED_CHAPTER_MAP),
        showReadingProgressLabels = store.getBoolean(KEY_SHOW_READING_PROGRESS_LABELS, FormattingPreferences.DEFAULT_SHOW_READING_PROGRESS_LABELS),
        showCurrentChapterLabel = store.getBoolean(KEY_SHOW_CURRENT_CHAPTER_LABEL, FormattingPreferences.DEFAULT_SHOW_CURRENT_CHAPTER_LABEL),
        showReadingTimeEstimate = store.getBoolean(KEY_SHOW_READING_TIME_ESTIMATE, FormattingPreferences.DEFAULT_SHOW_READING_TIME_ESTIMATE),
        doublePageSpread = store.getBoolean(KEY_DOUBLE_PAGE_SPREAD, FormattingPreferences.DEFAULT_DOUBLE_PAGE_SPREAD),
        forcePaginatedInLandscape = store.getBoolean(KEY_FORCE_PAGINATED_IN_LANDSCAPE, FormattingPreferences.DEFAULT_FORCE_PAGINATED_IN_LANDSCAPE),
        justifyText = store.getBoolean(KEY_JUSTIFY_TEXT, FormattingPreferences.DEFAULT_JUSTIFY_TEXT),
        autoScrollWpm = store.getInt(KEY_AUTO_SCROLL_WPM, FormattingPreferences.DEFAULT_AUTO_SCROLL_WPM),
        showAutoScroll = store.getBoolean(KEY_SHOW_AUTO_SCROLL, FormattingPreferences.DEFAULT_SHOW_AUTO_SCROLL),
        cadenceWpm = store.getInt(KEY_CADENCE_WPM, FormattingPreferences.DEFAULT_CADENCE_WPM),
        showCadence = store.getBoolean(KEY_SHOW_CADENCE, FormattingPreferences.DEFAULT_SHOW_CADENCE),
        cadenceHighlightColor = store.getString(KEY_CADENCE_HIGHLIGHT_COLOR)
            ?.let { runCatching { HighlightColor.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_CADENCE_HIGHLIGHT_COLOR,
        cadencePlatformSupported = store.getBoolean(KEY_CADENCE_PLATFORM_SUPPORTED, FormattingPreferences.DEFAULT_CADENCE_PLATFORM_SUPPORTED),
        autoReaderThemeMode = store.getString(KEY_AUTO_READER_THEME_MODE)
            ?.let { runCatching { AutoReaderThemeMode.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_AUTO_READER_THEME_MODE,
        appThemeReaderThemes = AppThemeReaderThemes(
            lightTheme = store.getString(KEY_APP_THEME_LIGHT_READER_THEME)
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ReaderTheme.Auto }
                ?: AppThemeReaderThemes.DEFAULT_LIGHT_THEME,
            darkTheme = store.getString(KEY_APP_THEME_DARK_READER_THEME)
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ReaderTheme.Auto }
                ?: AppThemeReaderThemes.DEFAULT_DARK_THEME,
        ),
        themeSchedule = ThemeSchedule(
            dayStart = store.getInt(KEY_SCHEDULE_DAY_START, -1)
                .takeIf { it >= 0 }?.let(::minuteOfDayToLocalTime)
                ?: ThemeSchedule.DEFAULT_DAY_START,
            nightStart = store.getInt(KEY_SCHEDULE_NIGHT_START, -1)
                .takeIf { it >= 0 }?.let(::minuteOfDayToLocalTime)
                ?: ThemeSchedule.DEFAULT_NIGHT_START,
            dayTheme = store.getString(KEY_SCHEDULE_DAY_THEME)
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ReaderTheme.Auto }
                ?: ThemeSchedule.DEFAULT_DAY_THEME,
            nightTheme = store.getString(KEY_SCHEDULE_NIGHT_THEME)
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ReaderTheme.Auto }
                ?: ThemeSchedule.DEFAULT_NIGHT_THEME,
        ),
    )

    private companion object {
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_THEME = "theme"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_MARGINS = "margins"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_SHOW_CHAPTER_MAP = "show_chapter_map"
        const val KEY_COLORED_CHAPTER_MAP = "colored_chapter_map"
        const val KEY_SHOW_READING_PROGRESS_LABELS = "show_reading_progress_labels"
        const val KEY_SHOW_CURRENT_CHAPTER_LABEL = "show_current_chapter_label"
        const val KEY_SHOW_READING_TIME_ESTIMATE = "show_reading_time_estimate"
        const val KEY_DOUBLE_PAGE_SPREAD = "double_page_spread"
        const val KEY_FORCE_PAGINATED_IN_LANDSCAPE = "force_paginated_in_landscape"
        const val KEY_JUSTIFY_TEXT = "justify_text"
        const val KEY_AUTO_SCROLL_WPM = "auto_scroll_wpm"
        const val KEY_SHOW_AUTO_SCROLL = "show_auto_scroll"
        const val KEY_CADENCE_WPM = "cadence_wpm"
        const val KEY_SHOW_CADENCE = "show_cadence"
        const val KEY_CADENCE_HIGHLIGHT_COLOR = "cadence_highlight_color"
        const val KEY_CADENCE_PLATFORM_SUPPORTED = "cadence_platform_supported"
        const val KEY_AUTO_READER_THEME_MODE = "auto_reader_theme_mode"
        const val KEY_APP_THEME_LIGHT_READER_THEME = "app_theme_light_reader_theme"
        const val KEY_APP_THEME_DARK_READER_THEME = "app_theme_dark_reader_theme"
        const val KEY_SCHEDULE_DAY_START = "theme_schedule_day_start_minute_of_day"
        const val KEY_SCHEDULE_NIGHT_START = "theme_schedule_night_start_minute_of_day"
        const val KEY_SCHEDULE_DAY_THEME = "theme_schedule_day_theme"
        const val KEY_SCHEDULE_NIGHT_THEME = "theme_schedule_night_theme"
    }
}

private fun LocalMinuteTime.toMinuteOfDay(): Int = hour * 60 + minute

private fun minuteOfDayToLocalTime(value: Int): LocalMinuteTime {
    val clamped = value.coerceIn(0, 24 * 60 - 1)
    return LocalMinuteTime.of(clamped / 60, clamped % 60)
}
