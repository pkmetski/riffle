package com.riffle.core.data

import com.riffle.core.domain.VolumeKeyPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

// Hardware volume-key interception is not possible on iOS (the OS handles volume keys before
// any app can consume them), so iOS ships no settings rows for it — see SettingsScreen's Behavior
// section. The preference is still stored, and its defaults match
// `VolumeKeyPreferencesStoreImpl` on Android (navigation on, inversion off) so the two platforms
// agree about an account's settings instead of reporting different values for the same user.
internal class IosVolumeKeyPreferencesStoreImpl : VolumeKeyPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val _volumeKeyNavigationEnabled = MutableStateFlow(
        if (defaults.objectForKey(KEY_ENABLED) != null) defaults.boolForKey(KEY_ENABLED) else true,
    )
    private val _invertVolumeKeys = MutableStateFlow(
        if (defaults.objectForKey(KEY_INVERT) != null) defaults.boolForKey(KEY_INVERT) else false,
    )

    override val volumeKeyNavigationEnabled: Flow<Boolean> = _volumeKeyNavigationEnabled
    override val invertVolumeKeys: Flow<Boolean> = _invertVolumeKeys

    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) {
        defaults.setBool(value, forKey = KEY_ENABLED)
        _volumeKeyNavigationEnabled.value = value
    }

    override suspend fun setInvertVolumeKeys(value: Boolean) {
        defaults.setBool(value, forKey = KEY_INVERT)
        _invertVolumeKeys.value = value
    }

    private companion object {
        const val KEY_ENABLED = "volume_key_navigation_enabled"
        const val KEY_INVERT = "invert_volume_keys"
    }
}
