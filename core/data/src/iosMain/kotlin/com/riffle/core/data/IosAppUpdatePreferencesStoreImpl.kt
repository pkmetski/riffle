package com.riffle.core.data

import com.riffle.core.domain.AppUpdatePreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

private const val KEY_AUTO_UPDATE_ENABLED = "app_update:auto_update_enabled"
private const val KEY_IGNORED_VERSION_CODE = "app_update:ignored_version_code"

/**
 * NSUserDefaults-backed [AppUpdatePreferencesStore] for iOS. Android's implementation is
 * DataStore-backed, which has no iOS equivalent, so this follows the same platform-store pattern
 * as [IosContentCacheAccessStoreImpl]: persist on write, and replay the current value through a
 * StateFlow so collectors update immediately rather than only after a restart (the previous stub
 * emitted a constant `false`/`0`, so the Settings toggles never reflected a change).
 */
class IosAppUpdatePreferencesStoreImpl : AppUpdatePreferencesStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    private val autoUpdateState = MutableStateFlow(defaults.boolForKey(KEY_AUTO_UPDATE_ENABLED))
    private val ignoredVersionState = MutableStateFlow(defaults.integerForKey(KEY_IGNORED_VERSION_CODE).toInt())

    override val autoUpdateEnabled: Flow<Boolean> = autoUpdateState.asStateFlow()

    override val ignoredVersionCode: Flow<Int> = ignoredVersionState.asStateFlow()

    override suspend fun setAutoUpdateEnabled(value: Boolean) {
        defaults.setBool(value, forKey = KEY_AUTO_UPDATE_ENABLED)
        autoUpdateState.value = value
    }

    override suspend fun setIgnoredVersionCode(value: Int) {
        defaults.setInteger(value.toLong(), forKey = KEY_IGNORED_VERSION_CODE)
        ignoredVersionState.value = value
    }
}
