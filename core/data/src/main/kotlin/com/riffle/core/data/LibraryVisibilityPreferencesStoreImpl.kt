package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.riffle.core.data.di.LibraryVisibilityPreferencesDataStore
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryVisibilityPreferencesStoreImpl @Inject constructor(
    @param:LibraryVisibilityPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : LibraryVisibilityPreferencesStore {

    override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[key(serverId)].orEmpty() }

    override suspend fun hideLibrary(serverId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(serverId)] = prefs[key(serverId)].orEmpty() + libraryId
        }
    }

    override suspend fun showLibrary(serverId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(serverId)] = prefs[key(serverId)].orEmpty() - libraryId
        }
    }

    private fun key(serverId: String) = stringSetPreferencesKey("hidden_$serverId")
}
