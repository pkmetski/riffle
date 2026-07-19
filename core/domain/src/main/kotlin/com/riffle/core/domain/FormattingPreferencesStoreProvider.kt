package com.riffle.core.domain
import com.riffle.core.models.FormattingScope

// Selects the global formatting-preferences store for a given reading context. Full-book and
// highlights each get their own DataStore instance so their global defaults are independent.
// Consumers that only ever want the full-book prefs (e.g. Settings screen) inject
// [FormattingPreferencesStore] directly and don't need this provider.
interface FormattingPreferencesStoreProvider {
    fun store(scope: FormattingScope): FormattingPreferencesStore
}
