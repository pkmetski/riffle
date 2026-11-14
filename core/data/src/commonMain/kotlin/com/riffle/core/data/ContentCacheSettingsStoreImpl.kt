package com.riffle.core.data

import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.ContentCacheSettingsStore.Companion.DEFAULT_AUTO_CLEAR
import kotlinx.coroutines.flow.Flow

class ContentCacheSettingsStoreImpl(private val store: KeyValueStore) : ContentCacheSettingsStore {

    override val autoClear: Flow<ContentCacheAutoClear> = store.observe {
        val stored = store.getString(KEY_AUTO_CLEAR)
        ContentCacheAutoClear.entries.firstOrNull { it.name == stored } ?: DEFAULT_AUTO_CLEAR
    }

    override suspend fun setAutoClear(value: ContentCacheAutoClear) =
        store.setString(KEY_AUTO_CLEAR, value.name)

    private companion object {
        const val KEY_AUTO_CLEAR = "auto_clear"
    }
}
