package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.WakeLockPreferencesStore
import platform.UIKit.UIApplication

/**
 * iOS NSUserDefaults-backed factories for stores unified with Android via the
 * [PreferenceStore] seam (see [appThemeStore], [readingSpeedStore], [wakeLockPreferencesStore]
 * in commonMain [PreferenceBackedStores]).
 *
 * Each factory creates an [IosPreferenceStore] with platform-typed read/write lambdas, then
 * hands it to the shared adapter in commonMain. The same NSUserDefaults keys as the old
 * hand-written impls are preserved so existing user data survives the migration.
 */

internal fun AppThemeStore(): AppThemeStore = appThemeStore(
    IosPreferenceStore(
        key = "app_theme",
        defaultValue = AppTheme.System,
        read = { defaults, key ->
            defaults.stringForKey(key)?.let { raw ->
                runCatching { AppTheme.valueOf(raw) }.getOrNull()
            }
        },
        write = { defaults, key, value -> defaults.setObject(value.name, forKey = key) },
    ),
)

internal fun ReadingSpeedStore(): ReadingSpeedStore = readingSpeedStore(
    IosPreferenceStore(
        key = "reading_speed_secs_per_position",
        defaultValue = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
        read = { defaults, key ->
            if (defaults.objectForKey(key) != null) defaults.doubleForKey(key) else null
        },
        write = { defaults, key, value -> defaults.setDouble(value, forKey = key) },
    ),
)

/**
 * Wraps the commonMain [wakeLockPreferencesStore] to apply the iOS-specific side effect:
 * syncing the preference value into [UIApplication.idleTimerDisabled] on every write.
 *
 * Note: the idle-timer is not set on app launch (matching the behaviour of the old
 * [IosWakeLockPreferencesStoreImpl]), so the UI layer is responsible for reading the
 * preference and applying it at startup.
 */
internal fun WakeLockPreferencesStore(): WakeLockPreferencesStore {
    val inner = wakeLockPreferencesStore(
        IosPreferenceStore(
            key = "keep_screen_on",
            defaultValue = true,
            read = { defaults, key ->
                if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
            },
            write = { defaults, key, value -> defaults.setBool(value, forKey = key) },
        ),
    )
    return object : WakeLockPreferencesStore {
        override val keepScreenOn = inner.keepScreenOn
        override suspend fun setKeepScreenOn(value: Boolean) {
            inner.setKeepScreenOn(value)
            UIApplication.sharedApplication.idleTimerDisabled = value
        }
    }
}
