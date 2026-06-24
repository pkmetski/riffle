package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.DeviceLabelDataStore
import com.riffle.core.domain.DeviceLabelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DeviceLabelStoreImpl @Inject constructor(
    @param:DeviceLabelDataStore private val dataStore: DataStore<Preferences>,
) : DeviceLabelStore {

    override fun observe(): Flow<String?> = dataStore.data.map { it[KEY_LABEL] }

    override suspend fun get(): String? = dataStore.data.map { it[KEY_LABEL] }.first()

    override suspend fun set(label: String?) {
        dataStore.edit { prefs ->
            val trimmed = label?.trim()?.take(MAX_LABEL_CHARS)
            if (trimmed.isNullOrEmpty()) prefs.remove(KEY_LABEL) else prefs[KEY_LABEL] = trimmed
        }
    }

    private companion object {
        const val MAX_LABEL_CHARS = 40
        val KEY_LABEL = stringPreferencesKey("device_label")
    }
}
