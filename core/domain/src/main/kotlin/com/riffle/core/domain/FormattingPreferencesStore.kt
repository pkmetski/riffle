package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface FormattingPreferencesStore {
    val preferences: Flow<FormattingPreferences>
    suspend fun update(preferences: FormattingPreferences)
}
