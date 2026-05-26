package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.FormattingPreferencesDataStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
            doublePageSpread = prefs[KEY_DOUBLE_PAGE_SPREAD] ?: false,
            justifyText = prefs[KEY_JUSTIFY_TEXT] ?: true,
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
            prefs[KEY_DOUBLE_PAGE_SPREAD] = preferences.doublePageSpread
            prefs[KEY_JUSTIFY_TEXT] = preferences.justifyText
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
        val KEY_DOUBLE_PAGE_SPREAD = booleanPreferencesKey("double_page_spread")
        val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justify_text")
    }
}
