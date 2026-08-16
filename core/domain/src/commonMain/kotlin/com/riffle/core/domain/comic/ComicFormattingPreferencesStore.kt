package com.riffle.core.domain.comic

import kotlinx.coroutines.flow.Flow

interface ComicFormattingPreferencesStore {
    val preferences: Flow<ComicFormattingPreferences>
    suspend fun update(prefs: ComicFormattingPreferences)
}
