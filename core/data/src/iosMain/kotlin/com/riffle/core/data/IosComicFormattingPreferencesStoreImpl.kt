package com.riffle.core.data

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed ComicFormattingPreferencesStore for iOS.
internal class IosComicFormattingPreferencesStoreImpl : ComicFormattingPreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val _preferences = MutableStateFlow(loadFromDefaults())

    override val preferences: Flow<ComicFormattingPreferences> = _preferences

    override suspend fun update(prefs: ComicFormattingPreferences) {
        defaults.setObject(prefs.backgroundTheme.name, forKey = KEY_BACKGROUND_THEME)
        defaults.setBool(prefs.panelViewOn, forKey = KEY_PANEL_VIEW_ON)
        defaults.setObject(prefs.panelOverflow.name, forKey = KEY_PANEL_OVERFLOW)
        defaults.setInteger(prefs.panelAnimationSpeedMs.toLong(), forKey = KEY_PANEL_ANIMATION_SPEED_MS)
        defaults.setBool(prefs.showChapterMap, forKey = KEY_SHOW_CHAPTER_MAP)
        defaults.setBool(prefs.showPageProgress, forKey = KEY_SHOW_PAGE_PROGRESS)
        _preferences.value = prefs
    }

    private fun loadFromDefaults(): ComicFormattingPreferences = ComicFormattingPreferences(
        backgroundTheme = defaults.stringForKey(KEY_BACKGROUND_THEME)
            ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: ReaderTheme.Dark,
        panelViewOn = boolOrDefault(KEY_PANEL_VIEW_ON, false),
        panelOverflow = defaults.stringForKey(KEY_PANEL_OVERFLOW)
            ?.let { runCatching { PanelOverflowBehavior.valueOf(it) }.getOrNull() }
            ?: PanelOverflowBehavior.SPLIT,
        panelAnimationSpeedMs = intOrDefault(KEY_PANEL_ANIMATION_SPEED_MS, 250),
        showChapterMap = boolOrDefault(KEY_SHOW_CHAPTER_MAP, false),
        showPageProgress = boolOrDefault(KEY_SHOW_PAGE_PROGRESS, false),
    )

    private fun boolOrDefault(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default

    private fun intOrDefault(key: String, default: Int): Int =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default

    private companion object {
        const val KEY_BACKGROUND_THEME = "comic_formatting.background_theme"
        const val KEY_PANEL_VIEW_ON = "comic_formatting.panel_view_on"
        const val KEY_PANEL_OVERFLOW = "comic_formatting.panel_overflow"
        const val KEY_PANEL_ANIMATION_SPEED_MS = "comic_formatting.panel_animation_speed_ms"
        const val KEY_SHOW_CHAPTER_MAP = "comic_formatting.show_chapter_map"
        const val KEY_SHOW_PAGE_PROGRESS = "comic_formatting.show_page_progress"
    }
}
