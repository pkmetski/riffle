package com.riffle.core.domain.comic

import kotlinx.coroutines.flow.Flow

interface BookComicFormattingPreferencesStore {
    fun overrides(bookId: String): Flow<BookComicFormattingOverrides>
    suspend fun save(bookId: String, overrides: BookComicFormattingOverrides)
    suspend fun reset(bookId: String)
}
