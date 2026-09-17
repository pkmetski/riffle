package com.riffle.core.data

import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.Flow

/**
 * Shared (commonMain) adapters that bind a [PreferenceStore] to a typed domain store interface.
 *
 * Both Android ([DataStorePreferenceStore]) and iOS ([IosPreferenceStore]) implement
 * [PreferenceStore], so the mapping from the raw pref value to the domain interface is written
 * once here and consumed on both platforms.
 */

internal fun appThemeStore(prefs: PreferenceStore<AppTheme>): AppThemeStore =
    object : AppThemeStore {
        override val appTheme: Flow<AppTheme> = prefs.flow
        override suspend fun setAppTheme(value: AppTheme) = prefs.update(value)
    }

internal fun readingSpeedStore(prefs: PreferenceStore<Double>): ReadingSpeedStore =
    object : ReadingSpeedStore {
        override val speedSecPerPosition: Flow<Double> = prefs.flow
        override suspend fun updateSpeed(newSecPerPosition: Double) = prefs.update(newSecPerPosition)
    }

internal fun wakeLockPreferencesStore(prefs: PreferenceStore<Boolean>): WakeLockPreferencesStore =
    object : WakeLockPreferencesStore {
        override val keepScreenOn: Flow<Boolean> = prefs.flow
        override suspend fun setKeepScreenOn(value: Boolean) = prefs.update(value)
    }
