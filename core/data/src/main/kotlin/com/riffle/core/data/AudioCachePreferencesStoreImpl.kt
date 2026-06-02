package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.riffle.core.data.di.AudioCachePreferencesDataStore
import com.riffle.core.domain.AudioCachePreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AudioCachePreferencesStoreImpl @Inject constructor(
    @param:AudioCachePreferencesDataStore private val dataStore: DataStore<Preferences>,
) : AudioCachePreferencesStore {

    override fun capBytes(serverId: String): Flow<Long> =
        dataStore.data.map { prefs -> prefs[key(serverId)] ?: AudioCachePreferencesStore.DEFAULT_CAP_BYTES }

    override suspend fun setCapBytes(serverId: String, capBytes: Long) {
        dataStore.edit { prefs -> prefs[key(serverId)] = capBytes.coerceAtLeast(0) }
    }

    private fun key(serverId: String) = longPreferencesKey("audio_cache_cap_$serverId")
}
