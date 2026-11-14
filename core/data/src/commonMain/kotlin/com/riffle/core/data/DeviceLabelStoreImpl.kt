package com.riffle.core.data

import com.riffle.core.domain.DeviceLabelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class DeviceLabelStoreImpl(private val store: KeyValueStore) : DeviceLabelStore {

    override fun observe(): Flow<String?> = store.observe { store.getString(KEY_LABEL) }

    override suspend fun get(): String? = store.getString(KEY_LABEL)

    override suspend fun set(label: String?) {
        val trimmed = label?.trim()?.take(MAX_LABEL_CHARS)
        store.setString(KEY_LABEL, trimmed?.takeIf { it.isNotEmpty() })
    }

    private companion object {
        const val KEY_LABEL = "device_label"
        const val MAX_LABEL_CHARS = 40
    }
}
