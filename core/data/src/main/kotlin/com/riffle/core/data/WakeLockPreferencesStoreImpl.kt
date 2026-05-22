package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.riffle.core.data.di.WakeLockPreferencesDataStore
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class WakeLockPreferencesStoreImpl @Inject constructor(
    @WakeLockPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : WakeLockPreferencesStore {

    override val keepScreenOn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEEP_SCREEN_ON] ?: true
    }

    override suspend fun setKeepScreenOn(value: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_KEEP_SCREEN_ON] = value }
    }

    private companion object {
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
