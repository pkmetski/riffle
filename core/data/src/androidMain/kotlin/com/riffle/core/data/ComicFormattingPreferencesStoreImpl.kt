package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ComicFormattingPreferencesStoreImpl constructor(
    private val dataStore: DataStore<Preferences>,
) : ComicFormattingPreferencesStore {

    override val preferences: Flow<ComicFormattingPreferences> = dataStore.data.map { prefs ->
        ComicFormattingPreferences(
            backgroundTheme = prefs[BACKGROUND_THEME]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull()?.asComicBackgroundTheme() }
                ?: ReaderTheme.Dark,
            panelViewOn = prefs[PANEL_VIEW_ON] ?: false,
            panelOverflow = prefs[PANEL_OVERFLOW]
                ?.let { runCatching { PanelOverflowBehavior.valueOf(it) }.getOrNull() }
                ?: PanelOverflowBehavior.SPLIT,
            panelAnimationSpeedMs = prefs[PANEL_ANIMATION_SPEED_MS] ?: 250,
            showChapterMap = prefs[SHOW_CHAPTER_MAP] ?: false,
            showPageProgress = prefs[SHOW_PAGE_PROGRESS] ?: false,
        )
    }

    override suspend fun update(prefs: ComicFormattingPreferences) {
        dataStore.edit { data ->
            val backgroundTheme = prefs.backgroundTheme.asComicBackgroundTheme()
            if (backgroundTheme == ReaderTheme.Dark) {
                data.remove(BACKGROUND_THEME)
            } else {
                data[BACKGROUND_THEME] = backgroundTheme.name
            }
            if (prefs.panelViewOn) data[PANEL_VIEW_ON] = true else data.remove(PANEL_VIEW_ON)
            if (prefs.panelOverflow == PanelOverflowBehavior.SPLIT) {
                data.remove(PANEL_OVERFLOW)
            } else {
                data[PANEL_OVERFLOW] = prefs.panelOverflow.name
            }
            if (prefs.panelAnimationSpeedMs == 250) {
                data.remove(PANEL_ANIMATION_SPEED_MS)
            } else {
                data[PANEL_ANIMATION_SPEED_MS] = prefs.panelAnimationSpeedMs
            }
            if (!prefs.showChapterMap) data.remove(SHOW_CHAPTER_MAP)
            else data[SHOW_CHAPTER_MAP] = true
            if (!prefs.showPageProgress) data.remove(SHOW_PAGE_PROGRESS)
            else data[SHOW_PAGE_PROGRESS] = true
        }
    }

    companion object {
        private val BACKGROUND_THEME = stringPreferencesKey("background_theme")
        private val PANEL_VIEW_ON = booleanPreferencesKey("panel_view_on")
        private val PANEL_OVERFLOW = stringPreferencesKey("panel_overflow")
        private val PANEL_ANIMATION_SPEED_MS = intPreferencesKey("panel_animation_speed_ms")
        private val SHOW_CHAPTER_MAP = booleanPreferencesKey("show_chapter_map")
        private val SHOW_PAGE_PROGRESS = booleanPreferencesKey("show_page_progress")
    }
}
