package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AppUpdatePreferencesStore {
    val autoUpdateEnabled: Flow<Boolean>
    val ignoredVersionCode: Flow<Int>
    suspend fun setAutoUpdateEnabled(value: Boolean)
    suspend fun setIgnoredVersionCode(value: Int)
}
