package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.LibraryFilterPreferencesDataStore
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryFilterPreferencesStoreImpl @Inject constructor(
    @param:LibraryFilterPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : LibraryFilterPreferencesStore {

    override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
        dataStore.data.map { prefs ->
            LibraryFilterPreferences(
                selectedFacetKey = prefs[facetKey(sourceId, libraryId)],
                notStartedFilterActive = prefs[notStartedKey(sourceId, libraryId)] ?: false,
                sortModeName = prefs[sortModeKey(sourceId, libraryId)],
            )
        }

    override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {
        dataStore.edit { prefs ->
            val prefKey = facetKey(sourceId, libraryId)
            if (key == null) prefs.remove(prefKey) else prefs[prefKey] = key
        }
    }

    override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {
        dataStore.edit { prefs ->
            prefs[notStartedKey(sourceId, libraryId)] = active
        }
    }

    override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {
        dataStore.edit { prefs ->
            val prefKey = sortModeKey(sourceId, libraryId)
            if (name == null) prefs.remove(prefKey) else prefs[prefKey] = name
        }
    }

    private fun facetKey(sourceId: String, libraryId: String) =
        stringPreferencesKey("library_filter:$sourceId:$libraryId:facet")

    private fun notStartedKey(sourceId: String, libraryId: String) =
        booleanPreferencesKey("library_filter:$sourceId:$libraryId:not_started")

    private fun sortModeKey(sourceId: String, libraryId: String) =
        stringPreferencesKey("library_filter:$sourceId:$libraryId:sort_mode")
}
