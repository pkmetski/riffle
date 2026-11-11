package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AppUpdatePreferencesStore {
    val autoUpdateEnabled: Flow<Boolean>
    val betaUpdatesEnabled: Flow<Boolean>
    val seenUpdateVersionCode: Flow<Int>
    suspend fun setAutoUpdateEnabled(value: Boolean)
    suspend fun setBetaUpdatesEnabled(value: Boolean)
    suspend fun setSeenUpdateVersionCode(value: Int)
}
