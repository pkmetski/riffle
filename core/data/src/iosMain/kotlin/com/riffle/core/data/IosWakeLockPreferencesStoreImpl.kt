package com.riffle.core.data

import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

class IosWakeLockPreferencesStoreImpl : WakeLockPreferencesStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val initialValue = if (defaults.objectForKey(KEY) != null) defaults.boolForKey(KEY) else DEFAULT
    private val _keepScreenOn = MutableStateFlow(initialValue)

    override val keepScreenOn: Flow<Boolean> = _keepScreenOn

    override suspend fun setKeepScreenOn(value: Boolean) {
        defaults.setBool(value, forKey = KEY)
        _keepScreenOn.value = value
        UIApplication.sharedApplication.idleTimerDisabled = value
    }

    private companion object {
        const val KEY = "keep_screen_on"
        const val DEFAULT = true
    }
}
