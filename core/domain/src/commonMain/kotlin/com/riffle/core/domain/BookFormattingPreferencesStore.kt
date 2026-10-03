package com.riffle.core.domain

import com.riffle.core.models.ScreenDimensionBucket

interface BookFormattingPreferencesStore {
    // Returns null if no row exists for this (sourceId, itemId, dimension) — caller is responsible for seeding.
    suspend fun load(sourceId: String, itemId: String, dimension: ScreenDimensionBucket): BookFormattingOverrides?
    suspend fun save(sourceId: String, itemId: String, dimension: ScreenDimensionBucket, overrides: BookFormattingOverrides)
    suspend fun clear(sourceId: String, itemId: String, dimension: ScreenDimensionBucket)
}
