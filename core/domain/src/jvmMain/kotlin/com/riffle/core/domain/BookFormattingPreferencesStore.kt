package com.riffle.core.domain

import com.riffle.core.models.FormattingScope
import com.riffle.core.models.ScreenDimensionBucket

interface BookFormattingPreferencesStore {
    // Returns null if no row exists for this (itemId, scope, dimension) — caller is responsible for seeding.
    suspend fun load(itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket): BookFormattingOverrides?
    suspend fun save(itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket, overrides: BookFormattingOverrides)
    suspend fun clear(itemId: String, scope: FormattingScope, dimension: ScreenDimensionBucket)
}
