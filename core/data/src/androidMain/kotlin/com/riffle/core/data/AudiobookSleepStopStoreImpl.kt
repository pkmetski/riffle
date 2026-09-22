package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.riffle.core.domain.AudiobookSleepStopStore
import kotlinx.coroutines.flow.first

class AudiobookSleepStopStoreImpl constructor(
    private val dataStore: DataStore<Preferences>,
) : AudiobookSleepStopStore {

    override suspend fun markSleepStopped(sourceId: String, itemId: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = prefs[KEY].orEmpty() + key(sourceId, itemId)
        }
    }

    override suspend fun clearSleepStopped(sourceId: String, itemId: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = prefs[KEY].orEmpty() - key(sourceId, itemId)
        }
    }

    override suspend fun wasSleepStopped(sourceId: String, itemId: String): Boolean =
        dataStore.data.first()[KEY]?.contains(key(sourceId, itemId)) == true

    private fun key(sourceId: String, itemId: String) = "$sourceId/$itemId"

    private companion object {
        val KEY = stringSetPreferencesKey("audiobook_sleep_stopped")
    }
}
