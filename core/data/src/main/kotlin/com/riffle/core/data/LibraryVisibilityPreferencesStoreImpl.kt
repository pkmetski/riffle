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

    override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[key(sourceId)].orEmpty() }

    override suspend fun hideLibrary(sourceId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(sourceId)] = prefs[key(sourceId)].orEmpty() + libraryId
        }
    }

    override suspend fun showLibrary(sourceId: String, libraryId: String) {
        dataStore.edit { prefs ->
            prefs[key(sourceId)] = prefs[key(sourceId)].orEmpty() - libraryId
        }
    }

    private fun key(sourceId: String) = stringSetPreferencesKey("hidden_$sourceId")
}
