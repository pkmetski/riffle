package com.riffle.core.domain
import com.riffle.core.models.FormattingScope

interface BookFormattingPreferencesStore {
    suspend fun load(itemId: String, scope: FormattingScope): BookFormattingOverrides
    suspend fun save(itemId: String, scope: FormattingScope, overrides: BookFormattingOverrides)
    suspend fun clear(itemId: String, scope: FormattingScope)
}
