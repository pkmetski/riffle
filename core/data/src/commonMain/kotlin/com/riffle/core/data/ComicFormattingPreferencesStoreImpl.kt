package com.riffle.core.data

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme
import kotlinx.coroutines.flow.Flow

class ComicFormattingPreferencesStoreImpl(private val store: KeyValueStore) : ComicFormattingPreferencesStore {

    override val preferences: Flow<ComicFormattingPreferences> = store.observe { load() }

    override suspend fun update(prefs: ComicFormattingPreferences) {
        val backgroundTheme = prefs.backgroundTheme.asComicBackgroundTheme()
        if (backgroundTheme == ReaderTheme.Dark) {
            store.remove(KEY_BACKGROUND_THEME)
        } else {
            store.setString(KEY_BACKGROUND_THEME, backgroundTheme.name)
        }
        if (prefs.panelViewOn) store.setBoolean(KEY_PANEL_VIEW_ON, true) else store.remove(KEY_PANEL_VIEW_ON)
        if (prefs.panelOverflow == PanelOverflowBehavior.SPLIT) {
            store.remove(KEY_PANEL_OVERFLOW)
        } else {
            store.setString(KEY_PANEL_OVERFLOW, prefs.panelOverflow.name)
        }
        if (prefs.panelAnimationSpeedMs == 250) {
            store.remove(KEY_PANEL_ANIMATION_SPEED_MS)
        } else {
            store.setInt(KEY_PANEL_ANIMATION_SPEED_MS, prefs.panelAnimationSpeedMs)
        }
        if (!prefs.showChapterMap) store.remove(KEY_SHOW_CHAPTER_MAP) else store.setBoolean(KEY_SHOW_CHAPTER_MAP, true)
        if (!prefs.showPageProgress) store.remove(KEY_SHOW_PAGE_PROGRESS) else store.setBoolean(KEY_SHOW_PAGE_PROGRESS, true)
    }

    private suspend fun load(): ComicFormattingPreferences = ComicFormattingPreferences(
        backgroundTheme = store.getString(KEY_BACKGROUND_THEME)
            ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull()?.asComicBackgroundTheme() }
            ?: ReaderTheme.Dark,
        panelViewOn = store.getBoolean(KEY_PANEL_VIEW_ON, false),
        panelOverflow = store.getString(KEY_PANEL_OVERFLOW)
            ?.let { runCatching { PanelOverflowBehavior.valueOf(it) }.getOrNull() }
            ?: PanelOverflowBehavior.SPLIT,
        panelAnimationSpeedMs = store.getInt(KEY_PANEL_ANIMATION_SPEED_MS, 250),
        showChapterMap = store.getBoolean(KEY_SHOW_CHAPTER_MAP, false),
        showPageProgress = store.getBoolean(KEY_SHOW_PAGE_PROGRESS, false),
    )

    private companion object {
        const val KEY_BACKGROUND_THEME = "background_theme"
        const val KEY_PANEL_VIEW_ON = "panel_view_on"
        const val KEY_PANEL_OVERFLOW = "panel_overflow"
        const val KEY_PANEL_ANIMATION_SPEED_MS = "panel_animation_speed_ms"
        const val KEY_SHOW_CHAPTER_MAP = "show_chapter_map"
        const val KEY_SHOW_PAGE_PROGRESS = "show_page_progress"
    }
}
