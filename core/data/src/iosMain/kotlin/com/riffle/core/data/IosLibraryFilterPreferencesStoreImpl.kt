package com.riffle.core.data

import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed library filter preferences, keyed per source+library.
// Key scheme mirrors the Android DataStore key names for cross-platform debugging symmetry.
internal class IosLibraryFilterPreferencesStoreImpl : LibraryFilterPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val flows = mutableMapOf<String, MutableStateFlow<LibraryFilterPreferences>>()

    override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
        stateFor(sourceId, libraryId)

    override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {
        update(sourceId, libraryId) { it.copy(selectedFacetKey = key) }
        if (key == null) {
            defaults.removeObjectForKey(facetKey(sourceId, libraryId))
        } else {
            defaults.setObject(key, forKey = facetKey(sourceId, libraryId))
        }
    }

    override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {
        update(sourceId, libraryId) { it.copy(notStartedFilterActive = active) }
        defaults.setBool(active, forKey = notStartedKey(sourceId, libraryId))
    }

    override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) {
        update(sourceId, libraryId) { it.copy(unownedFilterActive = active) }
        defaults.setBool(active, forKey = unownedKey(sourceId, libraryId))
    }

    override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {
        update(sourceId, libraryId) { it.copy(sortModeName = name) }
        if (name == null) {
            defaults.removeObjectForKey(sortModeKey(sourceId, libraryId))
        } else {
            defaults.setObject(name, forKey = sortModeKey(sourceId, libraryId))
        }
    }

    private fun stateFor(sourceId: String, libraryId: String): MutableStateFlow<LibraryFilterPreferences> {
        lock.lock()
        try {
            return flows.getOrPut("$sourceId:$libraryId") {
                MutableStateFlow(loadFromDefaults(sourceId, libraryId))
            }
        } finally {
            lock.unlock()
        }
    }

    private fun update(sourceId: String, libraryId: String, transform: (LibraryFilterPreferences) -> LibraryFilterPreferences) {
        stateFor(sourceId, libraryId).let { it.value = transform(it.value) }
    }

    private fun loadFromDefaults(sourceId: String, libraryId: String) = LibraryFilterPreferences(
        selectedFacetKey = defaults.stringForKey(facetKey(sourceId, libraryId)),
        notStartedFilterActive = if (defaults.objectForKey(notStartedKey(sourceId, libraryId)) != null) {
            defaults.boolForKey(notStartedKey(sourceId, libraryId))
        } else {
            false
        },
        sortModeName = defaults.stringForKey(sortModeKey(sourceId, libraryId)),
        unownedFilterActive = if (defaults.objectForKey(unownedKey(sourceId, libraryId)) != null) {
            defaults.boolForKey(unownedKey(sourceId, libraryId))
        } else {
            false
        },
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
