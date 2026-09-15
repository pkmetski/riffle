package com.riffle.core.data

import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

internal class IosReadaloudPreferencesStoreImpl : ReadaloudPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun readPrefs(): ReadaloudPreferences {
        val colorName = defaults.stringForKey(KEY_HIGHLIGHT_COLOR)
        val color = colorName?.let {
            HighlightColor.entries.firstOrNull { c -> c.name == it }
        } ?: ReadaloudPreferences().highlightColor
        return ReadaloudPreferences(highlightColor = color)
    }

    private val _preferences = MutableStateFlow(readPrefs())
    override val preferences: Flow<ReadaloudPreferences> = _preferences

    override suspend fun update(prefs: ReadaloudPreferences) {
        defaults.setObject(prefs.highlightColor.name, forKey = KEY_HIGHLIGHT_COLOR)
        _preferences.value = prefs
    }

    private companion object {
        const val KEY_HIGHLIGHT_COLOR = "readaloud.highlight_color"
    }
}
