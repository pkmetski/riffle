package com.riffle.core.data

import kotlinx.coroutines.flow.Flow

interface PreferenceStore<T> {
    val flow: Flow<T>
    suspend fun update(value: T)
}
