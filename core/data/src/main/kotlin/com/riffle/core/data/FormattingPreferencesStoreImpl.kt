package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.FormattingPreferencesDataStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.models.HighlightColor
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import javax.inject.Inject

class FormattingPreferencesStoreImpl @Inject constructor(
    @param:FormattingPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : FormattingPreferencesStore {

    override val preferences: Flow<FormattingPreferences> = dataStore.data.map { prefs ->
        FormattingPreferences(
            fontSize = prefs[KEY_FONT_SIZE] ?: 1.0f,
            theme = prefs[KEY_THEME]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?: ReaderTheme.Light,
            fontFamily = prefs[KEY_FONT_FAMILY]?.decodeFontFamily() ?: ReaderFontFamily.Original,
            lineSpacing = prefs[KEY_LINE_SPACING] ?: 1.2f,
            margins = prefs[KEY_MARGINS] ?: 1.0f,
            orientation = prefs[KEY_ORIENTATION]
                ?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() }
                ?: ReaderOrientation.Horizontal,
            showChapterMap = prefs[KEY_SHOW_CHAPTER_MAP] ?: true,
            showReadingProgressLabels = prefs[KEY_SHOW_READING_PROGRESS_LABELS] ?: false,
            showCurrentChapterLabel = prefs[KEY_SHOW_CURRENT_CHAPTER_LABEL] ?: false,
            showReadingTimeEstimate = prefs[KEY_SHOW_READING_TIME_ESTIMATE] ?: false,
            doublePageSpread = prefs[KEY_DOUBLE_PAGE_SPREAD] ?: false,
            justifyText = prefs[KEY_JUSTIFY_TEXT] ?: false,
            autoScrollWpm = prefs[KEY_AUTO_SCROLL_WPM] ?: FormattingPreferences.DEFAULT_AUTO_SCROLL_WPM,
            showAutoScroll = prefs[KEY_SHOW_AUTO_SCROLL] ?: FormattingPreferences.DEFAULT_SHOW_AUTO_SCROLL,
            cadenceWpm = prefs[KEY_CADENCE_WPM] ?: FormattingPreferences.DEFAULT_CADENCE_WPM,
            showCadence = prefs[KEY_SHOW_CADENCE] ?: FormattingPreferences.DEFAULT_SHOW_CADENCE,
            cadenceHighlightColor = prefs[KEY_CADENCE_HIGHLIGHT_COLOR]
                ?.let { runCatching { HighlightColor.valueOf(it) }.getOrNull() }
                ?: FormattingPreferences.DEFAULT_CADENCE_HIGHLIGHT_COLOR,
            cadencePlatformSupported = prefs[KEY_CADENCE_PLATFORM_SUPPORTED]
                ?: FormattingPreferences.DEFAULT_CADENCE_PLATFORM_SUPPORTED,
            themeSchedule = ThemeSchedule(
                dayStart = prefs[KEY_SCHEDULE_DAY_START]?.let(::minuteOfDayToLocalTime)
                    ?: ThemeSchedule.DEFAULT_DAY_START,
                nightStart = prefs[KEY_SCHEDULE_NIGHT_START]?.let(::minuteOfDayToLocalTime)
                    ?: ThemeSchedule.DEFAULT_NIGHT_START,
                dayTheme = prefs[KEY_SCHEDULE_DAY_THEME]
                    ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != ReaderTheme.Auto }
                    ?: ThemeSchedule.DEFAULT_DAY_THEME,
                nightTheme = prefs[KEY_SCHEDULE_NIGHT_THEME]
                    ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != ReaderTheme.Auto }
                    ?: ThemeSchedule.DEFAULT_NIGHT_THEME,
            ),
        )
    }

    override suspend fun update(preferences: FormattingPreferences) {
        dataStore.edit { prefs ->
            prefs[KEY_FONT_SIZE] = preferences.fontSize
            prefs[KEY_THEME] = preferences.theme.name
            prefs[KEY_FONT_FAMILY] = preferences.fontFamily.encodePersistName()
            prefs[KEY_LINE_SPACING] = preferences.lineSpacing
            prefs[KEY_MARGINS] = preferences.margins
            prefs[KEY_ORIENTATION] = preferences.orientation.name
            prefs[KEY_SHOW_CHAPTER_MAP] = preferences.showChapterMap
            prefs[KEY_SHOW_READING_PROGRESS_LABELS] = preferences.showReadingProgressLabels
            prefs[KEY_SHOW_CURRENT_CHAPTER_LABEL] = preferences.showCurrentChapterLabel
            prefs[KEY_SHOW_READING_TIME_ESTIMATE] = preferences.showReadingTimeEstimate
            prefs[KEY_DOUBLE_PAGE_SPREAD] = preferences.doublePageSpread
            prefs[KEY_JUSTIFY_TEXT] = preferences.justifyText
            prefs[KEY_AUTO_SCROLL_WPM] = preferences.autoScrollWpm
            prefs[KEY_SHOW_AUTO_SCROLL] = preferences.showAutoScroll
            prefs[KEY_CADENCE_WPM] = preferences.cadenceWpm
            prefs[KEY_SHOW_CADENCE] = preferences.showCadence
            prefs[KEY_CADENCE_HIGHLIGHT_COLOR] = preferences.cadenceHighlightColor.name
            prefs[KEY_SCHEDULE_DAY_START] = preferences.themeSchedule.dayStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_NIGHT_START] = preferences.themeSchedule.nightStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_DAY_THEME] = preferences.themeSchedule.dayTheme.name
            prefs[KEY_SCHEDULE_NIGHT_THEME] = preferences.themeSchedule.nightTheme.name
            // Intentionally NOT writing KEY_CADENCE_PLATFORM_SUPPORTED here — it is written only
            // by [setCadencePlatformSupported] so a concurrent user-driven prefs write cannot
            // clobber the reader's feature-detect result with a stale in-memory value.
        }
    }

    override suspend fun setCadencePlatformSupported(supported: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CADENCE_PLATFORM_SUPPORTED] = supported
        }
    }

    private companion object {
        val KEY_FONT_SIZE = floatPreferencesKey("font_size")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        val KEY_LINE_SPACING = floatPreferencesKey("line_spacing")
        val KEY_MARGINS = floatPreferencesKey("margins")
        val KEY_ORIENTATION = stringPreferencesKey("orientation")
        val KEY_SHOW_CHAPTER_MAP = booleanPreferencesKey("show_chapter_map")
        val KEY_SHOW_READING_PROGRESS_LABELS = booleanPreferencesKey("show_reading_progress_labels")
        val KEY_SHOW_CURRENT_CHAPTER_LABEL = booleanPreferencesKey("show_current_chapter_label")
        val KEY_SHOW_READING_TIME_ESTIMATE = booleanPreferencesKey("show_reading_time_estimate")
        val KEY_DOUBLE_PAGE_SPREAD = booleanPreferencesKey("double_page_spread")
        val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justify_text")
        val KEY_AUTO_SCROLL_WPM = intPreferencesKey("auto_scroll_wpm")
        val KEY_SHOW_AUTO_SCROLL = booleanPreferencesKey("show_auto_scroll")
        val KEY_CADENCE_WPM = intPreferencesKey("cadence_wpm")
        val KEY_SHOW_CADENCE = booleanPreferencesKey("show_cadence")
        val KEY_CADENCE_HIGHLIGHT_COLOR = stringPreferencesKey("cadence_highlight_color")
        val KEY_CADENCE_PLATFORM_SUPPORTED = booleanPreferencesKey("cadence_platform_supported")
        val KEY_SCHEDULE_DAY_START = intPreferencesKey("theme_schedule_day_start_minute_of_day")
        val KEY_SCHEDULE_NIGHT_START = intPreferencesKey("theme_schedule_night_start_minute_of_day")
        val KEY_SCHEDULE_DAY_THEME = stringPreferencesKey("theme_schedule_day_theme")
        val KEY_SCHEDULE_NIGHT_THEME = stringPreferencesKey("theme_schedule_night_theme")
    }
}

// Persisted-name codec for ReaderFontFamily.
//
// Before the Original split, the "Serif" enum value was passthrough — it rendered the publisher's
// font. Any legacy "Serif" string on disk therefore encoded that passthrough intent and must load
// as Original, not as the new real-serif Serif. To distinguish new picks from legacy data we
// persist the new Serif under a distinct name (SERIF_V2_PERSIST_NAME). All other values keep
// their enum name.
private const val SERIF_V2_PERSIST_NAME = "SerifV2"
private const val LEGACY_SERIF_PERSIST_NAME = "Serif"

internal fun ReaderFontFamily.encodePersistName(): String = when (this) {
    ReaderFontFamily.Serif -> SERIF_V2_PERSIST_NAME
    else -> name
}

internal fun String.decodeFontFamily(): ReaderFontFamily? = when (this) {
    SERIF_V2_PERSIST_NAME -> ReaderFontFamily.Serif
    LEGACY_SERIF_PERSIST_NAME -> ReaderFontFamily.Original
    else -> runCatching { ReaderFontFamily.valueOf(this) }.getOrNull()
}

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
private fun minuteOfDayToLocalTime(value: Int): LocalTime {
    // Clamp to the valid 24h range first so values like 1440 (24:00) don't silently
    // round-trip to 00:00 via the modulo on the next line.
    val clamped = value.coerceIn(0, 24 * 60 - 1)
    return LocalTime.of(clamped / 60, clamped % 60)
}
