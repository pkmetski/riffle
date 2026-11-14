package com.riffle.core.data

import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import kotlinx.coroutines.flow.Flow

class LibraryVisibilityPreferencesStoreImpl(private val store: KeyValueStore) : LibraryVisibilityPreferencesStore {

    override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
        store.observe { store.getStringSet(key(sourceId)) }

    override suspend fun hideLibrary(sourceId: String, libraryId: String) {
        val updated = store.getStringSet(key(sourceId)) + libraryId
        store.setStringSet(key(sourceId), updated)
    }

    override suspend fun showLibrary(sourceId: String, libraryId: String) {
        val updated = store.getStringSet(key(sourceId)) - libraryId
        store.setStringSet(key(sourceId), updated)
    }

    private fun key(sourceId: String) = "hidden_$sourceId"
}
