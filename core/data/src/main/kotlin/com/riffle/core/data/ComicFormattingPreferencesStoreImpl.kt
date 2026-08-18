package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.ComicFormattingPreferencesDataStore
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.ComicFormattingPreferencesStore
import com.riffle.core.domain.comic.PanelOverflowBehavior
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ComicFormattingPreferencesStoreImpl @Inject constructor(
    @param:ComicFormattingPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : ComicFormattingPreferencesStore {

    override val preferences: Flow<ComicFormattingPreferences> = dataStore.data.map { prefs ->
        ComicFormattingPreferences(
            panelViewOn = prefs[PANEL_VIEW_ON] ?: false,
            panelOverflow = prefs[PANEL_OVERFLOW]
                ?.let { runCatching { PanelOverflowBehavior.valueOf(it) }.getOrNull() }
                ?: PanelOverflowBehavior.SPLIT,
            panelAnimationSpeedMs = prefs[PANEL_ANIMATION_SPEED_MS] ?: 250,
        )
    }

    override suspend fun update(prefs: ComicFormattingPreferences) {
        dataStore.edit { data ->
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
        }
    }

    companion object {
        private val PANEL_VIEW_ON = booleanPreferencesKey("panel_view_on")
        private val PANEL_OVERFLOW = stringPreferencesKey("panel_overflow")
        private val PANEL_ANIMATION_SPEED_MS = intPreferencesKey("panel_animation_speed_ms")
    }
}
