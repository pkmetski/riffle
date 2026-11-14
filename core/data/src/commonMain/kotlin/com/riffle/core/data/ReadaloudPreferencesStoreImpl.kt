package com.riffle.core.data

import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.Flow

class ReadaloudPreferencesStoreImpl(private val store: KeyValueStore) : ReadaloudPreferencesStore {

    override val preferences: Flow<ReadaloudPreferences> = store.observe {
        val colorName = store.getString(KEY_HIGHLIGHT_COLOR)
        val color = colorName?.let { raw ->
            HighlightColor.entries.firstOrNull { it.name == raw }
        } ?: ReadaloudPreferences().highlightColor
        ReadaloudPreferences(highlightColor = color)
    }

    override suspend fun update(prefs: ReadaloudPreferences) =
        store.setString(KEY_HIGHLIGHT_COLOR, prefs.highlightColor.name)

    private companion object {
        const val KEY_HIGHLIGHT_COLOR = "highlight_color"
    }
}
