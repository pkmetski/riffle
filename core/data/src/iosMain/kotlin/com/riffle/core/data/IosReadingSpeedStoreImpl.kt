package com.riffle.core.data

import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

internal class IosReadingSpeedStoreImpl : ReadingSpeedStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val _speedSecPerPosition = MutableStateFlow(
        if (defaults.objectForKey(KEY) != null) defaults.doubleForKey(KEY)
        else ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
    )

    override val speedSecPerPosition: Flow<Double> = _speedSecPerPosition

    override suspend fun updateSpeed(newSecPerPosition: Double) {
        defaults.setDouble(newSecPerPosition, forKey = KEY)
        _speedSecPerPosition.value = newSecPerPosition
    }

    private companion object {
        const val KEY = "reading_speed_secs_per_position"
    }
}
