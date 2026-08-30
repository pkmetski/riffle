package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.LastOpenedLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LastOpenedLibraryStoreImpl constructor(
    private val dataStore: DataStore<Preferences>,
) : LastOpenedLibraryStore {

    override fun lastOpenedLibrary(sourceId: String): Flow<String?> =
        dataStore.data.map { prefs -> prefs[key(sourceId)] }

    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(sourceId)] = libraryId
        }
    }

    private fun key(sourceId: String) = stringPreferencesKey("last_$sourceId")
}
