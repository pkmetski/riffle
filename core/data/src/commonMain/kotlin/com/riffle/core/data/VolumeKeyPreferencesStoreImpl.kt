package com.riffle.core.data

import com.riffle.core.domain.VolumeKeyPreferencesStore
import kotlinx.coroutines.flow.Flow

class VolumeKeyPreferencesStoreImpl(private val store: KeyValueStore) : VolumeKeyPreferencesStore {

    override val volumeKeyNavigationEnabled: Flow<Boolean> =
        store.observe { store.getBoolean(KEY_ENABLED, true) }

    override val invertVolumeKeys: Flow<Boolean> =
        store.observe { store.getBoolean(KEY_INVERT, false) }

    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) =
        store.setBoolean(KEY_ENABLED, value)

    override suspend fun setInvertVolumeKeys(value: Boolean) =
        store.setBoolean(KEY_INVERT, value)

    private companion object {
        const val KEY_ENABLED = "volume_key_navigation_enabled"
        const val KEY_INVERT = "invert_volume_keys"
    }
}
