package com.riffle.core.data

import com.riffle.core.domain.LibraryOrderPreferencesStore
import kotlinx.coroutines.flow.Flow

class LibraryOrderPreferencesStoreImpl(private val store: KeyValueStore) : LibraryOrderPreferencesStore {

    override fun libraryOrder(sourceId: String): Flow<List<String>> =
        store.observe { decode(store.getString(key(sourceId))) }

    override suspend fun setLibraryOrder(sourceId: String, orderedIds: List<String>) =
        store.setString(key(sourceId), orderedIds.joinToString("\n"))

    private fun key(sourceId: String) = "order_$sourceId"

    private fun decode(raw: String?): List<String> =
        raw?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
}
