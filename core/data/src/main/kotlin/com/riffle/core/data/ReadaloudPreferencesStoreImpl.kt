package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.ReadaloudPreferencesDataStore
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ReadaloudPreferencesStoreImpl @Inject constructor(
    @param:ReadaloudPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : ReadaloudPreferencesStore {

    override val preferences: Flow<ReadaloudPreferences> = dataStore.data.map { prefs ->
        ReadaloudPreferences(
            highlightColor = prefs[KEY_HIGHLIGHT_COLOR]
                ?.let { runCatching { ReadaloudHighlightColor.valueOf(it) }.getOrNull() }
                ?: ReadaloudHighlightColor.BLUE,
        )
    }

    override suspend fun update(prefs: ReadaloudPreferences) {
        dataStore.edit { it[KEY_HIGHLIGHT_COLOR] = prefs.highlightColor.name }
    }

    private companion object {
        val KEY_HIGHLIGHT_COLOR = stringPreferencesKey("highlight_color")
    }
}
