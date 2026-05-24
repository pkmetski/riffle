package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.riffle.core.data.di.VolumeKeyPreferencesDataStore
import com.riffle.core.domain.VolumeKeyPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VolumeKeyPreferencesStoreImpl @Inject constructor(
    @VolumeKeyPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : VolumeKeyPreferencesStore {

    override val volumeKeyNavigationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VOLUME_KEY_NAVIGATION_ENABLED] ?: true
    }

    override val invertVolumeKeys: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_INVERT_VOLUME_KEYS] ?: false
    }

    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VOLUME_KEY_NAVIGATION_ENABLED] = value }
    }

    override suspend fun setInvertVolumeKeys(value: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INVERT_VOLUME_KEYS] = value }
    }

    private companion object {
        val KEY_VOLUME_KEY_NAVIGATION_ENABLED = booleanPreferencesKey("volume_key_navigation_enabled")
        val KEY_INVERT_VOLUME_KEYS = booleanPreferencesKey("invert_volume_keys")
    }
}
