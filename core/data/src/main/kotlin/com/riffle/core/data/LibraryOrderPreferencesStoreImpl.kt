package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.LibraryOrderPreferencesDataStore
import com.riffle.core.domain.LibraryOrderPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryOrderPreferencesStoreImpl @Inject constructor(
    @param:LibraryOrderPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : LibraryOrderPreferencesStore {

    // Order matters, so we can't use a Preferences string-set (unordered). We persist the ordered
    // ids as a single newline-delimited string; library ids are server-issued and never contain
    // newlines.
    override fun libraryOrder(serverId: String): Flow<List<String>> =
        dataStore.data.map { prefs ->
            prefs[key(serverId)]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        }

    override suspend fun setLibraryOrder(serverId: String, orderedIds: List<String>) {
        dataStore.edit { prefs ->
            prefs[key(serverId)] = orderedIds.joinToString("\n")
        }
    }

    private fun key(serverId: String) = stringPreferencesKey("order_$serverId")
}
