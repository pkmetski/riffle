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
            fontFamily = prefs[KEY_FONT_FAMILY]
                ?.let { runCatching { ReaderFontFamily.valueOf(it) }.getOrNull() }
                ?: ReaderFontFamily.Serif,
            lineSpacing = prefs[KEY_LINE_SPACING] ?: 1.2f,
            margins = prefs[KEY_MARGINS] ?: 1.0f,
            orientation = prefs[KEY_ORIENTATION]
                ?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() }
                ?: ReaderOrientation.Horizontal,
            showChapterMap = prefs[KEY_SHOW_CHAPTER_MAP] ?: true,
            showReadingProgressLabels = prefs[KEY_SHOW_READING_PROGRESS_LABELS] ?: false,
            showCurrentChapterLabel = prefs[KEY_SHOW_CURRENT_CHAPTER_LABEL] ?: false,
            doublePageSpread = prefs[KEY_DOUBLE_PAGE_SPREAD] ?: false,
            justifyText = prefs[KEY_JUSTIFY_TEXT] ?: false,
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
            prefs[KEY_FONT_FAMILY] = preferences.fontFamily.name
            prefs[KEY_LINE_SPACING] = preferences.lineSpacing
            prefs[KEY_MARGINS] = preferences.margins
            prefs[KEY_ORIENTATION] = preferences.orientation.name
            prefs[KEY_SHOW_CHAPTER_MAP] = preferences.showChapterMap
            prefs[KEY_SHOW_READING_PROGRESS_LABELS] = preferences.showReadingProgressLabels
            prefs[KEY_SHOW_CURRENT_CHAPTER_LABEL] = preferences.showCurrentChapterLabel
            prefs[KEY_DOUBLE_PAGE_SPREAD] = preferences.doublePageSpread
            prefs[KEY_JUSTIFY_TEXT] = preferences.justifyText
            prefs[KEY_SCHEDULE_DAY_START] = preferences.themeSchedule.dayStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_NIGHT_START] = preferences.themeSchedule.nightStart.toMinuteOfDay()
            prefs[KEY_SCHEDULE_DAY_THEME] = preferences.themeSchedule.dayTheme.name
            prefs[KEY_SCHEDULE_NIGHT_THEME] = preferences.themeSchedule.nightTheme.name
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
        val KEY_DOUBLE_PAGE_SPREAD = booleanPreferencesKey("double_page_spread")
        val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justify_text")
        val KEY_SCHEDULE_DAY_START = intPreferencesKey("theme_schedule_day_start_minute_of_day")
        val KEY_SCHEDULE_NIGHT_START = intPreferencesKey("theme_schedule_night_start_minute_of_day")
        val KEY_SCHEDULE_DAY_THEME = stringPreferencesKey("theme_schedule_day_theme")
        val KEY_SCHEDULE_NIGHT_THEME = stringPreferencesKey("theme_schedule_night_theme")
    }
}

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
private fun minuteOfDayToLocalTime(value: Int): LocalTime =
    LocalTime.of((value / 60).coerceIn(0, 23), (value % 60).coerceIn(0, 59))
