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
    private val bookshelfActiveFlow = MutableStateFlow(
        defaults.boolForKey(BOOKSHELF_ACTIVE_KEY)
    )

    override fun lastOpenedLibrary(sourceId: String): Flow<String?> = stateFor(sourceId)

    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
        defaults.setObject(libraryId, forKey = key(sourceId))
        defaults.removeObjectForKey(BOOKSHELF_ACTIVE_KEY)
        stateFor(sourceId).value = libraryId
        bookshelfActiveFlow.value = false
    }

    override fun wasBookshelfLastActive(): Flow<Boolean> = bookshelfActiveFlow

    override suspend fun setBookshelfActive() {
        defaults.setBool(true, forKey = BOOKSHELF_ACTIVE_KEY)
        bookshelfActiveFlow.value = true
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
        private const val BOOKSHELF_ACTIVE_KEY = "last_dest_bookshelf"
    }
}
