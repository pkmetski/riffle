package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.WakeLockPreferencesStore

fun AppThemeStore(dataStore: DataStore<Preferences>): AppThemeStore = appThemeStore(
    preferenceStore(dataStore, PrefCodecs.enum("app_theme", AppTheme.System, AppTheme.entries.toTypedArray())),
)

fun ReadingSpeedStore(dataStore: DataStore<Preferences>): ReadingSpeedStore = readingSpeedStore(
    preferenceStore(
        dataStore,
        PrefCodecs.double("reading_speed_secs_per_position", default = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION),
    ),
)

fun WakeLockPreferencesStore(dataStore: DataStore<Preferences>): WakeLockPreferencesStore =
    wakeLockPreferencesStore(preferenceStore(dataStore, PrefCodecs.boolean("keep_screen_on", default = true)))
