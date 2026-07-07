package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface FormattingPreferencesStore {
    val preferences: Flow<FormattingPreferences>
    suspend fun update(preferences: FormattingPreferences)

    // Persist only the Cadence platform-capability flag. Kept off the general update() path so a
    // concurrent user-driven preference write cannot silently overwrite the reader's WebView
    // feature-detect result. The reader calls this once per open book when the JS probe reports
    // its Intl.Segmenter answer.
    suspend fun setCadencePlatformSupported(supported: Boolean)
}
