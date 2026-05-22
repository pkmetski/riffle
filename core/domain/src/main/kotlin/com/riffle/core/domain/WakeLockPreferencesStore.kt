package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface WakeLockPreferencesStore {
    val keepScreenOn: Flow<Boolean>
    suspend fun setKeepScreenOn(value: Boolean)
}
