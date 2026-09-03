package com.riffle.core.data

import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

class IosLibraryVisibilityPreferencesStoreImpl : LibraryVisibilityPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val flows = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> = stateFor(sourceId)

    override suspend fun hideLibrary(sourceId: String, libraryId: String) {
        val updated = stateFor(sourceId).value + libraryId
        persist(sourceId, updated)
    }

    override suspend fun showLibrary(sourceId: String, libraryId: String) {
        val updated = stateFor(sourceId).value - libraryId
        persist(sourceId, updated)
    }

    private fun stateFor(sourceId: String): MutableStateFlow<Set<String>> =
        flows.getOrPut(sourceId) {
            @Suppress("UNCHECKED_CAST")
            val saved = defaults.stringArrayForKey(key(sourceId)) as? List<String>
            MutableStateFlow(saved?.toSet() ?: emptySet())
        }

    private fun persist(sourceId: String, ids: Set<String>) {
        defaults.setObject(ids.toList(), forKey = key(sourceId))
        stateFor(sourceId).value = ids
    }

    private fun key(sourceId: String) = "hidden_libs:$sourceId"
}
