package com.riffle.core.data

import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import kotlinx.coroutines.flow.Flow

class LibraryFilterPreferencesStoreImpl(private val store: KeyValueStore) : LibraryFilterPreferencesStore {

    override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
        store.observe { load(sourceId, libraryId) }

    override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) =
        store.setString(facetKey(sourceId, libraryId), key)

    override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) =
        store.setBoolean(notStartedKey(sourceId, libraryId), active)

    override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) =
        store.setBoolean(unownedKey(sourceId, libraryId), active)

    override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) =
        store.setString(sortModeKey(sourceId, libraryId), name)

    private suspend fun load(sourceId: String, libraryId: String) = LibraryFilterPreferences(
        selectedFacetKey = store.getString(facetKey(sourceId, libraryId)),
        notStartedFilterActive = store.getBoolean(notStartedKey(sourceId, libraryId), false),
        sortModeName = store.getString(sortModeKey(sourceId, libraryId)),
        unownedFilterActive = store.getBoolean(unownedKey(sourceId, libraryId), false),
    )

    private fun facetKey(sourceId: String, libraryId: String) =
        "library_filter:$sourceId:$libraryId:facet"

    private fun notStartedKey(sourceId: String, libraryId: String) =
        "library_filter:$sourceId:$libraryId:not_started"

    private fun unownedKey(sourceId: String, libraryId: String) =
        "library_filter:$sourceId:$libraryId:unowned"

    private fun sortModeKey(sourceId: String, libraryId: String) =
        "library_filter:$sourceId:$libraryId:sort_mode"
}
