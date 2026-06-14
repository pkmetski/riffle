package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.LastOpenedLibraryDataStore
import com.riffle.core.domain.LastOpenedLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LastOpenedLibraryStoreImpl @Inject constructor(
    @param:LastOpenedLibraryDataStore private val dataStore: DataStore<Preferences>,
) : LastOpenedLibraryStore {

    override fun lastOpenedLibrary(serverId: String): Flow<String?> =
        dataStore.data.map { prefs -> prefs[key(serverId)] }

    override suspend fun setLastOpenedLibrary(serverId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(serverId)] = libraryId
        }
    }

    private fun key(serverId: String) = stringPreferencesKey("last_$serverId")
}
