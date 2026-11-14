package com.riffle.core.data

import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.models.HighlightColor
import kotlinx.coroutines.flow.Flow

class HighlightColorPreferencesStoreImpl(private val store: KeyValueStore) : HighlightColorPreferencesStore {

    override fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor> =
        store.observe {
            val name = store.getString(colorKey(sourceId, itemId))
            if (name == null) HighlightColor.DEFAULT
            else runCatching { HighlightColor.valueOf(name) }.getOrDefault(HighlightColor.DEFAULT)
        }

    override suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor) =
        store.setString(colorKey(sourceId, itemId), value.name)

    override fun lastUsedIsNone(sourceId: String, itemId: String): Flow<Boolean> =
        store.observe { store.getBoolean(noneKey(sourceId, itemId), false) }

    override suspend fun setLastUsedIsNone(sourceId: String, itemId: String, value: Boolean) {
        if (value) store.setBoolean(noneKey(sourceId, itemId), true)
        else store.remove(noneKey(sourceId, itemId))
    }

    private fun colorKey(sourceId: String, itemId: String) =
        "last_used_highlight_color:$sourceId:$itemId"

    private fun noneKey(sourceId: String, itemId: String) =
        "last_used_highlight_is_none:$sourceId:$itemId"
}
