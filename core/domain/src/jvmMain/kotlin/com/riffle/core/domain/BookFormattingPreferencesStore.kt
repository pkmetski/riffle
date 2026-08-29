package com.riffle.core.domain

import com.riffle.core.models.ScreenDimensionBucket

interface BookFormattingPreferencesStore {
    // Returns null if no row exists for this (itemId, dimension) — caller is responsible for seeding.
    suspend fun load(itemId: String, dimension: ScreenDimensionBucket): BookFormattingOverrides?
    suspend fun save(itemId: String, dimension: ScreenDimensionBucket, overrides: BookFormattingOverrides)
    suspend fun clear(itemId: String, dimension: ScreenDimensionBucket)
}
