package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface VolumeKeyPreferencesStore {
    val volumeKeyNavigationEnabled: Flow<Boolean>
    val invertVolumeKeys: Flow<Boolean>
    suspend fun setVolumeKeyNavigationEnabled(value: Boolean)
    suspend fun setInvertVolumeKeys(value: Boolean)
}
