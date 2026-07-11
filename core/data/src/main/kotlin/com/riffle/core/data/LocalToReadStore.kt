package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.riffle.core.data.di.LocalToReadDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only "To Read" backing used by [ToReadRepositoryImpl] when the active Source's Catalog
 * doesn't implement [com.riffle.core.catalog.PlaylistsCapability] — i.e. every Source without a
 * server-side playlist API (Local Files today, Chitanka, any future backend-less Source).
 *
 * Persisted per-libraryId as a plain string set in a Preferences DataStore. There is no cache-vs-
 * server reconciliation because there is no server — the DataStore IS the source of truth.
 */
interface LocalToReadStore {
    fun observeItemIds(libraryId: String): Flow<Set<String>>
    suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean
    suspend fun add(libraryId: String, libraryItemId: String)
    suspend fun remove(libraryId: String, libraryItemId: String)
}

@Singleton
class LocalToReadStoreImpl @Inject constructor(
    @param:LocalToReadDataStore private val dataStore: DataStore<Preferences>,
) : LocalToReadStore {

    override fun observeItemIds(libraryId: String): Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[key(libraryId)].orEmpty() }

    override suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean =
        dataStore.data.first()[key(libraryId)]?.contains(libraryItemId) == true

    override suspend fun add(libraryId: String, libraryItemId: String) {
        dataStore.edit { prefs ->
            prefs[key(libraryId)] = prefs[key(libraryId)].orEmpty() + libraryItemId
        }
    }

    override suspend fun remove(libraryId: String, libraryItemId: String) {
        dataStore.edit { prefs ->
            prefs[key(libraryId)] = prefs[key(libraryId)].orEmpty() - libraryItemId
        }
    }

    private fun key(libraryId: String) = stringSetPreferencesKey("to_read_$libraryId")
}
