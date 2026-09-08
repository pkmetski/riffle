package com.riffle.core.data

import com.riffle.core.domain.LastOpenedLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults

class IosLastOpenedLibraryStoreImpl : LastOpenedLibraryStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()
    private val riffleActiveFlow = MutableStateFlow(
        defaults.boolForKey(RIFFLE_ACTIVE_KEY)
    )

    override fun lastOpenedLibrary(sourceId: String): Flow<String?> = stateFor(sourceId)

    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
        defaults.setObject(libraryId, forKey = key(sourceId))
        defaults.removeObjectForKey(RIFFLE_ACTIVE_KEY)
        stateFor(sourceId).value = libraryId
        riffleActiveFlow.value = false
    }

    override fun wasRiffleLastActive(): Flow<Boolean> = riffleActiveFlow

    override suspend fun setRiffleActive() {
        defaults.setBool(true, forKey = RIFFLE_ACTIVE_KEY)
        riffleActiveFlow.value = true
    }

    override suspend fun clearRiffleActive() {
        defaults.removeObjectForKey(RIFFLE_ACTIVE_KEY)
        riffleActiveFlow.value = false
    }

    private fun stateFor(sourceId: String): MutableStateFlow<String?> {
        lock.lock()
        try {
            return flows.getOrPut(sourceId) {
                MutableStateFlow(defaults.stringForKey(key(sourceId)))
            }
        } finally {
            lock.unlock()
        }
    }

    private fun key(sourceId: String) = "last_lib:$sourceId"

    companion object {
        private const val RIFFLE_ACTIVE_KEY = "last_dest_riffle"
    }
}
