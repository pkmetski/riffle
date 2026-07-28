package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ReadingSpeedStore {
    val speedSecPerPosition: Flow<Double>
    suspend fun updateSpeed(newSecPerPosition: Double)
}
