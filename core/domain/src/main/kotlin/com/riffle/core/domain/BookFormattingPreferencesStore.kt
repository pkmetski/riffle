package com.riffle.core.domain

interface BookFormattingPreferencesStore {
    suspend fun load(itemId: String): FormattingPreferences?
    suspend fun save(itemId: String, preferences: FormattingPreferences)
    suspend fun clear(itemId: String)
}
