package com.riffle.core.data

import com.riffle.core.domain.LibraryOrderPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed library order store. Order is persisted as a newline-delimited string,
// matching the Android DataStore codec so cross-platform debugging is straightforward.
internal class IosLibraryOrderPreferencesStoreImpl : LibraryOrderPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val flows = mutableMapOf<String, MutableStateFlow<List<String>>>()

    override fun libraryOrder(sourceId: String): Flow<List<String>> = stateFor(sourceId)

    override suspend fun setLibraryOrder(sourceId: String, orderedIds: List<String>) {
        defaults.setObject(orderedIds.joinToString("\n"), forKey = key(sourceId))
        stateFor(sourceId).value = orderedIds
    }

    private fun stateFor(sourceId: String): MutableStateFlow<List<String>> {
        lock.lock()
        try {
            return flows.getOrPut(sourceId) {
                val saved = defaults.stringForKey(key(sourceId))
                    ?.split('\n')
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                MutableStateFlow(saved)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun key(sourceId: String) = "order_$sourceId"
}
