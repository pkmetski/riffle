package com.riffle.core.data

import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider
import com.riffle.core.models.FormattingScope

// Selects the right global store instance for a reading context. Both instances are `Singleton` in
// Hilt so calling this repeatedly is free — no per-call DataStore construction.
class FormattingPreferencesStoreProviderImpl(
    private val fullBook: FormattingPreferencesStore,
    private val highlights: FormattingPreferencesStore,
) : FormattingPreferencesStoreProvider {
    override fun store(scope: FormattingScope): FormattingPreferencesStore = when (scope) {
        FormattingScope.FullBook -> fullBook
        FormattingScope.Highlights -> highlights
    }
}
