package com.riffle.core.data

import com.riffle.core.domain.AppUpdatePreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

private const val KEY_AUTO_UPDATE_ENABLED = "app_update:auto_update_enabled"
private const val KEY_BETA_UPDATES_ENABLED = "app_update:beta_updates_enabled"
private const val KEY_SEEN_UPDATE_VERSION_CODE = "app_update:seen_update_version_code"

class IosAppUpdatePreferencesStoreImpl : AppUpdatePreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    private val autoUpdateState = MutableStateFlow(
        if (defaults.objectForKey(KEY_AUTO_UPDATE_ENABLED) != null) defaults.boolForKey(KEY_AUTO_UPDATE_ENABLED) else true
    )
    private val betaUpdatesState = MutableStateFlow(
        if (defaults.objectForKey(KEY_BETA_UPDATES_ENABLED) != null) defaults.boolForKey(KEY_BETA_UPDATES_ENABLED) else false
    )
    private val seenVersionState = MutableStateFlow(defaults.integerForKey(KEY_SEEN_UPDATE_VERSION_CODE).toInt())

    override val autoUpdateEnabled: Flow<Boolean> = autoUpdateState.asStateFlow()
    override val betaUpdatesEnabled: Flow<Boolean> = betaUpdatesState.asStateFlow()
    override val seenUpdateVersionCode: Flow<Int> = seenVersionState.asStateFlow()

    override suspend fun setAutoUpdateEnabled(value: Boolean) {
        defaults.setBool(value, forKey = KEY_AUTO_UPDATE_ENABLED)
        autoUpdateState.value = value
    }

    override suspend fun setBetaUpdatesEnabled(value: Boolean) {
        defaults.setBool(value, forKey = KEY_BETA_UPDATES_ENABLED)
        betaUpdatesState.value = value
    }

    override suspend fun setSeenUpdateVersionCode(value: Int) {
        defaults.setInteger(value.toLong(), forKey = KEY_SEEN_UPDATE_VERSION_CODE)
        seenVersionState.value = value
    }
}
