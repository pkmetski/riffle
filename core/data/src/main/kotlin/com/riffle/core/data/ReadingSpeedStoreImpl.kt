package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import com.riffle.core.data.di.ReadingSpeedDataStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ReadingSpeedStoreImpl @Inject constructor(
    @param:ReadingSpeedDataStore private val dataStore: DataStore<Preferences>,
) : ReadingSpeedStore {

    override val speedSecPerPosition: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_SECS_PER_POSITION] ?: ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION
    }

    override suspend fun updateSpeed(newSecPerPosition: Double) {
        dataStore.edit { prefs -> prefs[KEY_SECS_PER_POSITION] = newSecPerPosition }
    }

    private companion object {
        val KEY_SECS_PER_POSITION = doublePreferencesKey("reading_speed_secs_per_position")
    }
}
