package com.riffle.core.domain

interface BookFormattingPreferencesStore {
    suspend fun load(itemId: String): BookFormattingOverrides
    suspend fun save(itemId: String, overrides: BookFormattingOverrides)
    suspend fun clear(itemId: String)
}
