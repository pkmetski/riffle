package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.WakeLockPreferencesStore

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
 * Plain preference store — no UIKit side effect.
 *
 * It used to flip [platform.UIKit.UIApplication.idleTimerDisabled] inside its own setter, which
 * made "keep screen on while reading" both app-wide (the screen also stayed awake on the library
 * and settings screens) and non-durable (nothing re-applied it at launch). The idle timer is now
 * driven by the reader screens via `ReaderWakeLock`, mirroring Android's window-scoped
 * `FLAG_KEEP_SCREEN_ON` (#1071 §15.3).
 */
internal fun WakeLockPreferencesStore(): WakeLockPreferencesStore = wakeLockPreferencesStore(
    IosPreferenceStore(
        key = "keep_screen_on",
        defaultValue = true,
        read = { defaults, key ->
            if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
        },
        write = { defaults, key, value -> defaults.setBool(value, forKey = key) },
    ),
)
