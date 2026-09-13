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
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed FormattingPreferencesStore for iOS.
// Mirrors the key names from the Android DataStore implementation to ease cross-platform debugging.
// Uses the same codec (SerifV2 for Serif, run-catching on enum decode).
internal class IosFormattingPreferencesStoreImpl : FormattingPreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val _prefs = MutableStateFlow(loadFromDefaults())

    override val preferences: Flow<FormattingPreferences> = _prefs

    override suspend fun update(preferences: FormattingPreferences) {
        defaults.setDouble(preferences.fontSize.toDouble(), forKey = KEY_FONT_SIZE)
        defaults.setObject(preferences.theme.name, forKey = KEY_THEME)
        defaults.setObject(preferences.fontFamily.encodePersistName(), forKey = KEY_FONT_FAMILY)
        defaults.setDouble(preferences.lineSpacing.toDouble(), forKey = KEY_LINE_SPACING)
        defaults.setDouble(preferences.margins.toDouble(), forKey = KEY_MARGINS)
        defaults.setObject(preferences.orientation.name, forKey = KEY_ORIENTATION)
        defaults.setBool(preferences.showChapterMap, forKey = KEY_SHOW_CHAPTER_MAP)
        defaults.setBool(preferences.coloredChapterMap, forKey = KEY_COLORED_CHAPTER_MAP)
        defaults.setBool(preferences.showReadingProgressLabels, forKey = KEY_SHOW_READING_PROGRESS_LABELS)
        defaults.setBool(preferences.showCurrentChapterLabel, forKey = KEY_SHOW_CURRENT_CHAPTER_LABEL)
        defaults.setBool(preferences.showReadingTimeEstimate, forKey = KEY_SHOW_READING_TIME_ESTIMATE)
        defaults.setBool(preferences.doublePageSpread, forKey = KEY_DOUBLE_PAGE_SPREAD)
        defaults.setBool(preferences.justifyText, forKey = KEY_JUSTIFY_TEXT)
        defaults.setInteger(preferences.autoScrollWpm.toLong(), forKey = KEY_AUTO_SCROLL_WPM)
        defaults.setBool(preferences.showAutoScroll, forKey = KEY_SHOW_AUTO_SCROLL)
        defaults.setInteger(preferences.cadenceWpm.toLong(), forKey = KEY_CADENCE_WPM)
        defaults.setBool(preferences.showCadence, forKey = KEY_SHOW_CADENCE)
        defaults.setObject(preferences.cadenceHighlightColor.name, forKey = KEY_CADENCE_HIGHLIGHT_COLOR)
        defaults.setObject(preferences.autoReaderThemeMode.name, forKey = KEY_AUTO_READER_THEME_MODE)
        defaults.setObject(preferences.appThemeReaderThemes.lightTheme.name, forKey = KEY_APP_THEME_LIGHT_READER_THEME)
        defaults.setObject(preferences.appThemeReaderThemes.darkTheme.name, forKey = KEY_APP_THEME_DARK_READER_THEME)
        defaults.setInteger(preferences.themeSchedule.dayStart.toMinuteOfDay().toLong(), forKey = KEY_SCHEDULE_DAY_START)
        defaults.setInteger(preferences.themeSchedule.nightStart.toMinuteOfDay().toLong(), forKey = KEY_SCHEDULE_NIGHT_START)
        defaults.setObject(preferences.themeSchedule.dayTheme.name, forKey = KEY_SCHEDULE_DAY_THEME)
        defaults.setObject(preferences.themeSchedule.nightTheme.name, forKey = KEY_SCHEDULE_NIGHT_THEME)
        _prefs.value = preferences
    }

    override suspend fun setCadencePlatformSupported(supported: Boolean) {
        defaults.setBool(supported, forKey = KEY_CADENCE_PLATFORM_SUPPORTED)
        _prefs.value = _prefs.value.copy(cadencePlatformSupported = supported)
    }

    private fun loadFromDefaults(): FormattingPreferences = FormattingPreferences(
        fontSize = defaults.doubleForKey(KEY_FONT_SIZE).let { if (it == 0.0) FormattingPreferences.DEFAULT_FONT_SIZE else it.toFloat() },
        theme = defaults.stringForKey(KEY_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_THEME,
        fontFamily = defaults.stringForKey(KEY_FONT_FAMILY)?.decodeFontFamily()
            ?: FormattingPreferences.DEFAULT_FONT_FAMILY,
        lineSpacing = defaults.doubleForKey(KEY_LINE_SPACING).let { if (it == 0.0) FormattingPreferences.DEFAULT_LINE_SPACING else it.toFloat() },
        margins = defaults.doubleForKey(KEY_MARGINS).let { if (it == 0.0) FormattingPreferences.DEFAULT_MARGINS else it.toFloat() },
        orientation = defaults.stringForKey(KEY_ORIENTATION)?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_ORIENTATION,
        showChapterMap = if (defaults.objectForKey(KEY_SHOW_CHAPTER_MAP) != null) defaults.boolForKey(KEY_SHOW_CHAPTER_MAP) else FormattingPreferences.DEFAULT_SHOW_CHAPTER_MAP,
        coloredChapterMap = if (defaults.objectForKey(KEY_COLORED_CHAPTER_MAP) != null) defaults.boolForKey(KEY_COLORED_CHAPTER_MAP) else FormattingPreferences.DEFAULT_COLORED_CHAPTER_MAP,
        showReadingProgressLabels = if (defaults.objectForKey(KEY_SHOW_READING_PROGRESS_LABELS) != null) defaults.boolForKey(KEY_SHOW_READING_PROGRESS_LABELS) else FormattingPreferences.DEFAULT_SHOW_READING_PROGRESS_LABELS,
        showCurrentChapterLabel = if (defaults.objectForKey(KEY_SHOW_CURRENT_CHAPTER_LABEL) != null) defaults.boolForKey(KEY_SHOW_CURRENT_CHAPTER_LABEL) else FormattingPreferences.DEFAULT_SHOW_CURRENT_CHAPTER_LABEL,
        showReadingTimeEstimate = if (defaults.objectForKey(KEY_SHOW_READING_TIME_ESTIMATE) != null) defaults.boolForKey(KEY_SHOW_READING_TIME_ESTIMATE) else FormattingPreferences.DEFAULT_SHOW_READING_TIME_ESTIMATE,
        doublePageSpread = if (defaults.objectForKey(KEY_DOUBLE_PAGE_SPREAD) != null) defaults.boolForKey(KEY_DOUBLE_PAGE_SPREAD) else FormattingPreferences.DEFAULT_DOUBLE_PAGE_SPREAD,
        justifyText = if (defaults.objectForKey(KEY_JUSTIFY_TEXT) != null) defaults.boolForKey(KEY_JUSTIFY_TEXT) else FormattingPreferences.DEFAULT_JUSTIFY_TEXT,
        autoScrollWpm = if (defaults.objectForKey(KEY_AUTO_SCROLL_WPM) != null) defaults.integerForKey(KEY_AUTO_SCROLL_WPM).toInt() else FormattingPreferences.DEFAULT_AUTO_SCROLL_WPM,
        showAutoScroll = if (defaults.objectForKey(KEY_SHOW_AUTO_SCROLL) != null) defaults.boolForKey(KEY_SHOW_AUTO_SCROLL) else FormattingPreferences.DEFAULT_SHOW_AUTO_SCROLL,
        cadenceWpm = if (defaults.objectForKey(KEY_CADENCE_WPM) != null) defaults.integerForKey(KEY_CADENCE_WPM).toInt() else FormattingPreferences.DEFAULT_CADENCE_WPM,
        showCadence = if (defaults.objectForKey(KEY_SHOW_CADENCE) != null) defaults.boolForKey(KEY_SHOW_CADENCE) else FormattingPreferences.DEFAULT_SHOW_CADENCE,
        cadenceHighlightColor = defaults.stringForKey(KEY_CADENCE_HIGHLIGHT_COLOR)?.let { runCatching { HighlightColor.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_CADENCE_HIGHLIGHT_COLOR,
        cadencePlatformSupported = if (defaults.objectForKey(KEY_CADENCE_PLATFORM_SUPPORTED) != null) defaults.boolForKey(KEY_CADENCE_PLATFORM_SUPPORTED) else FormattingPreferences.DEFAULT_CADENCE_PLATFORM_SUPPORTED,
        autoReaderThemeMode = defaults.stringForKey(KEY_AUTO_READER_THEME_MODE)?.let { runCatching { AutoReaderThemeMode.valueOf(it) }.getOrNull() }
            ?: FormattingPreferences.DEFAULT_AUTO_READER_THEME_MODE,
        appThemeReaderThemes = AppThemeReaderThemes(
            lightTheme = defaults.stringForKey(KEY_APP_THEME_LIGHT_READER_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }?.takeIf { it != ReaderTheme.Auto }
                ?: AppThemeReaderThemes.DEFAULT_LIGHT_THEME,
            darkTheme = defaults.stringForKey(KEY_APP_THEME_DARK_READER_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }?.takeIf { it != ReaderTheme.Auto }
                ?: AppThemeReaderThemes.DEFAULT_DARK_THEME,
        ),
        themeSchedule = ThemeSchedule(
            dayStart = defaults.objectForKey(KEY_SCHEDULE_DAY_START)?.let { minuteOfDayToLocalTime(defaults.integerForKey(KEY_SCHEDULE_DAY_START).toInt()) }
                ?: ThemeSchedule.DEFAULT_DAY_START,
            nightStart = defaults.objectForKey(KEY_SCHEDULE_NIGHT_START)?.let { minuteOfDayToLocalTime(defaults.integerForKey(KEY_SCHEDULE_NIGHT_START).toInt()) }
                ?: ThemeSchedule.DEFAULT_NIGHT_START,
            dayTheme = defaults.stringForKey(KEY_SCHEDULE_DAY_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }?.takeIf { it != ReaderTheme.Auto }
                ?: ThemeSchedule.DEFAULT_DAY_THEME,
            nightTheme = defaults.stringForKey(KEY_SCHEDULE_NIGHT_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }?.takeIf { it != ReaderTheme.Auto }
                ?: ThemeSchedule.DEFAULT_NIGHT_THEME,
        ),
    )

    private companion object {
        const val KEY_FONT_SIZE = "formatting.font_size"
        const val KEY_THEME = "formatting.theme"
        const val KEY_FONT_FAMILY = "formatting.font_family"
        const val KEY_LINE_SPACING = "formatting.line_spacing"
        const val KEY_MARGINS = "formatting.margins"
        const val KEY_ORIENTATION = "formatting.orientation"
        const val KEY_SHOW_CHAPTER_MAP = "formatting.show_chapter_map"
        const val KEY_COLORED_CHAPTER_MAP = "formatting.colored_chapter_map"
        const val KEY_SHOW_READING_PROGRESS_LABELS = "formatting.show_reading_progress_labels"
        const val KEY_SHOW_CURRENT_CHAPTER_LABEL = "formatting.show_current_chapter_label"
        const val KEY_SHOW_READING_TIME_ESTIMATE = "formatting.show_reading_time_estimate"
        const val KEY_DOUBLE_PAGE_SPREAD = "formatting.double_page_spread"
        const val KEY_JUSTIFY_TEXT = "formatting.justify_text"
        const val KEY_AUTO_SCROLL_WPM = "formatting.auto_scroll_wpm"
        const val KEY_SHOW_AUTO_SCROLL = "formatting.show_auto_scroll"
        const val KEY_CADENCE_WPM = "formatting.cadence_wpm"
        const val KEY_SHOW_CADENCE = "formatting.show_cadence"
        const val KEY_CADENCE_HIGHLIGHT_COLOR = "formatting.cadence_highlight_color"
        const val KEY_CADENCE_PLATFORM_SUPPORTED = "formatting.cadence_platform_supported"
        const val KEY_AUTO_READER_THEME_MODE = "formatting.auto_reader_theme_mode"
        const val KEY_APP_THEME_LIGHT_READER_THEME = "formatting.app_theme_light_reader_theme"
        const val KEY_APP_THEME_DARK_READER_THEME = "formatting.app_theme_dark_reader_theme"
        const val KEY_SCHEDULE_DAY_START = "formatting.theme_schedule_day_start_minute_of_day"
        const val KEY_SCHEDULE_NIGHT_START = "formatting.theme_schedule_night_start_minute_of_day"
        const val KEY_SCHEDULE_DAY_THEME = "formatting.theme_schedule_day_theme"
        const val KEY_SCHEDULE_NIGHT_THEME = "formatting.theme_schedule_night_theme"
    }
}

private fun LocalMinuteTime.toMinuteOfDay(): Int = hour * 60 + minute

private fun minuteOfDayToLocalTime(value: Int): LocalMinuteTime {
    val clamped = value.coerceIn(0, 24 * 60 - 1)
    return LocalMinuteTime.of(clamped / 60, clamped % 60)
}

// Same codec as Android to round-trip identically.
private const val SERIF_V2_PERSIST_NAME = "SerifV2"
private const val LEGACY_SERIF_PERSIST_NAME = "Serif"

private fun ReaderFontFamily.encodePersistName(): String = when (this) {
    ReaderFontFamily.Serif -> SERIF_V2_PERSIST_NAME
    else -> name
}

private fun String.decodeFontFamily(): ReaderFontFamily? = when (this) {
    SERIF_V2_PERSIST_NAME -> ReaderFontFamily.Serif
    LEGACY_SERIF_PERSIST_NAME -> ReaderFontFamily.Original
    else -> runCatching { ReaderFontFamily.valueOf(this) }.getOrNull()
}
