package com.riffle.core.data

import com.riffle.core.domain.AppUpdatePreferencesStore
import kotlinx.coroutines.flow.Flow

class AppUpdatePreferencesStoreImpl(private val store: KeyValueStore) : AppUpdatePreferencesStore {

    override val autoUpdateEnabled: Flow<Boolean> =
        store.observe { store.getBoolean(KEY_AUTO_UPDATE, true) }

    override val betaUpdatesEnabled: Flow<Boolean> =
        store.observe { store.getBoolean(KEY_BETA_UPDATES, false) }

    override val seenUpdateVersionCode: Flow<Int> =
        store.observe { store.getInt(KEY_SEEN_VERSION, 0) }

    override suspend fun setAutoUpdateEnabled(value: Boolean) =
        store.setBoolean(KEY_AUTO_UPDATE, value)

    override suspend fun setBetaUpdatesEnabled(value: Boolean) =
        store.setBoolean(KEY_BETA_UPDATES, value)

    override suspend fun setSeenUpdateVersionCode(value: Int) =
        store.setInt(KEY_SEEN_VERSION, value)

    private companion object {
        const val KEY_AUTO_UPDATE = "auto_update_enabled"
        const val KEY_BETA_UPDATES = "beta_updates_enabled"
        const val KEY_SEEN_VERSION = "seen_update_version_code"
    }
}
