package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class LocalToReadStoreImpl constructor(
    private val dataStore: DataStore<Preferences>,
) : LocalToReadStore {

    override fun observeItemIds(libraryId: String): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[itemKey(libraryId)].orEmpty() }

    override suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean =
        dataStore.data.first()[itemKey(libraryId)]?.contains(libraryItemId) == true

    override suspend fun add(libraryId: String, libraryItemId: String) {
        dataStore.edit { prefs ->
            prefs[itemKey(libraryId)] = prefs[itemKey(libraryId)].orEmpty() + libraryItemId
            prefs[tsKey(libraryId)] = System.currentTimeMillis()
        }
    }

    override suspend fun remove(libraryId: String, libraryItemId: String) {
        dataStore.edit { prefs ->
            prefs[itemKey(libraryId)] = prefs[itemKey(libraryId)].orEmpty() - libraryItemId
            prefs[tsKey(libraryId)] = System.currentTimeMillis()
        }
    }

    override suspend fun lastUpdateMs(libraryId: String): Long =
        dataStore.data.first()[tsKey(libraryId)] ?: 0L

    override suspend fun setAll(libraryId: String, itemIds: Set<String>, lastUpdateMs: Long) {
        dataStore.edit { prefs ->
            prefs[itemKey(libraryId)] = itemIds
            prefs[tsKey(libraryId)] = lastUpdateMs
        }
    }

    private fun itemKey(libraryId: String) = stringSetPreferencesKey("to_read_$libraryId")
    private fun tsKey(libraryId: String) = longPreferencesKey("to_read_ts_$libraryId")
}
