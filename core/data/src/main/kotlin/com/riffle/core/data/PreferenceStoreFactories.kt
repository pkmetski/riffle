package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single-key plain `DataStore<Preferences>` wrappers — one factory per typed store.
 * The interface name is reused (Kotlin function-with-type-name idiom, c.f.
 * `mutableListOf`, `Channel`) so callers and tests look like `AppThemeStore(ds)`.
 *
 * Multi-key stores (Volume, Listening, Formatting, …) and stores with custom
 * logic stay as hand-written classes — they don't fit the single-codec shape.
 */

fun AppThemeStore(dataStore: DataStore<Preferences>): AppThemeStore {
    val store = preferenceStore(
        dataStore,
        PrefCodecs.enum("app_theme", AppTheme.System, AppTheme.entries.toTypedArray()),
    )
    return object : AppThemeStore {
        override val appTheme: Flow<AppTheme> = store.flow
        override suspend fun setAppTheme(value: AppTheme) = store.update(value)
    }
}

fun CoverGridDensityStore(dataStore: DataStore<Preferences>): CoverGridDensityStore {
    val store = preferenceStore(dataStore, PrefCodecs.float("cover_grid_scale", default = 1f))
    return object : CoverGridDensityStore {
        override val scale: Flow<Float> = store.flow
        override suspend fun setScale(value: Float) = store.update(value)
    }
}

fun ReadingSpeedStore(dataStore: DataStore<Preferences>): ReadingSpeedStore {
    val store = preferenceStore(
        dataStore,
        PrefCodecs.double(
            "reading_speed_secs_per_position",
            default = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
        ),
    )
    return object : ReadingSpeedStore {
        override val speedSecPerPosition: Flow<Double> = store.flow
        override suspend fun updateSpeed(newSecPerPosition: Double) = store.update(newSecPerPosition)
    }
}

fun WakeLockPreferencesStore(dataStore: DataStore<Preferences>): WakeLockPreferencesStore {
    val store = preferenceStore(dataStore, PrefCodecs.boolean("keep_screen_on", default = true))
    return object : WakeLockPreferencesStore {
        override val keepScreenOn: Flow<Boolean> = store.flow
        override suspend fun setKeepScreenOn(value: Boolean) = store.update(value)
    }
}

fun ReadaloudPreferencesStore(dataStore: DataStore<Preferences>): ReadaloudPreferencesStore {
    val store = preferenceStore(
        dataStore,
        PrefCodecs.enum(
            "highlight_color",
            ReadaloudHighlightColor.BLUE,
            ReadaloudHighlightColor.entries.toTypedArray(),
        ),
    )
    return object : ReadaloudPreferencesStore {
        override val preferences: Flow<ReadaloudPreferences> =
            store.flow.map { ReadaloudPreferences(highlightColor = it) }

        override suspend fun update(prefs: ReadaloudPreferences) = store.update(prefs.highlightColor)
    }
}
