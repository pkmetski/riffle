package com.riffle.core.data

import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.models.EmphasisStyle
import kotlinx.coroutines.flow.Flow

class EmphasisPreferencesStoreImpl(private val store: KeyValueStore) : EmphasisPreferencesStore {

    override fun lastUsedStyles(sourceId: String, itemId: String): Flow<Set<EmphasisStyle>> =
        store.observe { EmphasisStyle.decode(store.getString(key(sourceId, itemId))) ?: emptySet() }

    override suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>) {
        val encoded = EmphasisStyle.encode(value)
        store.setString(key(sourceId, itemId), encoded)
    }

    private fun key(sourceId: String, itemId: String) = "last_used_emphasis_styles:$sourceId:$itemId"
}
