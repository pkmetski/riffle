package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.DeviceIdDataStore
import com.riffle.core.domain.DeviceIdStore
import java.util.UUID
import javax.inject.Inject

class DeviceIdStoreImpl @Inject constructor(
    @param:DeviceIdDataStore private val dataStore: DataStore<Preferences>,
) : DeviceIdStore {

    override suspend fun getOrCreate(): String {
        // Mint inside the atomic edit so concurrent first-run callers agree on one value.
        var result = ""
        dataStore.edit { prefs ->
            val id = prefs[KEY_DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[KEY_DEVICE_ID] = it }
            result = id
        }
        return result
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}
