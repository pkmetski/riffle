package com.riffle.core.data

import com.riffle.core.domain.LastOpenedLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

class IosLastOpenedLibraryStoreImpl : LastOpenedLibraryStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    override fun lastOpenedLibrary(sourceId: String): Flow<String?> = stateFor(sourceId)

    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
        defaults.setObject(libraryId, forKey = key(sourceId))
        stateFor(sourceId).value = libraryId
    }

    private fun stateFor(sourceId: String): MutableStateFlow<String?> =
        flows.getOrPut(sourceId) {
            MutableStateFlow(defaults.stringForKey(key(sourceId)))
        }

    private fun key(sourceId: String) = "last_lib:$sourceId"
}
