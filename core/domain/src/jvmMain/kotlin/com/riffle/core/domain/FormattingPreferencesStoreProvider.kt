package com.riffle.core.domain

// Returns the single global formatting-preferences store. Both the full-book reader and the
// elided (annotations) reader share the same global store so display settings propagate to both
// views automatically. Consumers that only ever want global prefs (e.g. Settings screen) inject
// [FormattingPreferencesStore] directly and don't need this provider.
interface FormattingPreferencesStoreProvider {
    fun store(): FormattingPreferencesStore
}
