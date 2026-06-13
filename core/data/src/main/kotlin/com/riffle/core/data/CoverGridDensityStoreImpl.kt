package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.riffle.core.data.di.CoverGridDensityDataStore
import com.riffle.core.domain.CoverGridDensityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CoverGridDensityStoreImpl @Inject constructor(
    @param:CoverGridDensityDataStore private val dataStore: DataStore<Preferences>,
) : CoverGridDensityStore {

    override val scale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_SCALE] ?: DEFAULT_SCALE
    }

    override suspend fun setScale(value: Float) {
        dataStore.edit { prefs -> prefs[KEY_SCALE] = value }
    }

    private companion object {
        const val DEFAULT_SCALE = 1f
        val KEY_SCALE = floatPreferencesKey("cover_grid_scale")
    }
}
