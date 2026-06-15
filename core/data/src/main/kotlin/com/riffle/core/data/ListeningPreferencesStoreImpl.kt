package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.riffle.core.data.di.ListeningPreferencesDataStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_ON_RESUME_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_SKIP_INTERVAL_SECONDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ListeningPreferencesStoreImpl @Inject constructor(
    @param:ListeningPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : ListeningPreferencesStore {

    override val defaultPlaybackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED
    }

    override val skipIntervalSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SKIP_INTERVAL_SECONDS] ?: DEFAULT_SKIP_INTERVAL_SECONDS
    }

    override val rewindOnResumeSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REWIND_ON_RESUME_SECONDS] ?: DEFAULT_REWIND_ON_RESUME_SECONDS
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        dataStore.edit { prefs -> prefs[KEY_DEFAULT_PLAYBACK_SPEED] = speed }
    }

    override suspend fun setSkipIntervalSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_SKIP_INTERVAL_SECONDS] = seconds }
    }

    override suspend fun setRewindOnResumeSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_REWIND_ON_RESUME_SECONDS] = seconds }
    }

    private companion object {
        val KEY_DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        val KEY_SKIP_INTERVAL_SECONDS = intPreferencesKey("skip_interval_seconds")
        val KEY_REWIND_ON_RESUME_SECONDS = intPreferencesKey("rewind_on_resume_seconds")
    }
}
