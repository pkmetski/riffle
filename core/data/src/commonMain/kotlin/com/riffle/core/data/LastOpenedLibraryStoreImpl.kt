package com.riffle.core.data

import com.riffle.core.domain.LastOpenedLibraryStore
import kotlinx.coroutines.flow.Flow

class LastOpenedLibraryStoreImpl(private val store: KeyValueStore) : LastOpenedLibraryStore {

    override fun lastOpenedLibrary(sourceId: String): Flow<String?> =
        store.observe { store.getString(key(sourceId)) }

    override suspend fun setLastOpenedLibrary(sourceId: String, libraryId: String) {
        store.setString(key(sourceId), libraryId)
        store.remove(KEY_RIFFLE_ACTIVE)
    }

    override fun wasRiffleLastActive(): Flow<Boolean> =
        store.observe { store.getBoolean(KEY_RIFFLE_ACTIVE, false) }

    override suspend fun setRiffleActive() = store.setBoolean(KEY_RIFFLE_ACTIVE, true)

    override suspend fun clearRiffleActive() = store.remove(KEY_RIFFLE_ACTIVE)

    private fun key(sourceId: String) = "last_$sourceId"

    private companion object {
        const val KEY_RIFFLE_ACTIVE = "last_dest_riffle"
    }
}
