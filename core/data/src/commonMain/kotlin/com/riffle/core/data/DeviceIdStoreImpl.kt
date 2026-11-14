package com.riffle.core.data

import com.riffle.core.common.randomUuidString
import com.riffle.core.domain.DeviceIdStore

class DeviceIdStoreImpl(private val store: KeyValueStore) : DeviceIdStore {

    override suspend fun getOrCreate(): String =
        store.updateString(KEY_DEVICE_ID) { current -> current ?: randomUuidString() }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
