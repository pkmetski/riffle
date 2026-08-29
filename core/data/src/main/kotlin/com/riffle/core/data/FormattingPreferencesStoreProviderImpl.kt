package com.riffle.core.data

import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferencesStoreProvider

// Returns the single global formatting-preferences store shared by all reading contexts. The
// singleton is free to call repeatedly — no per-call DataStore construction.
class FormattingPreferencesStoreProviderImpl(
    private val fullBook: FormattingPreferencesStore,
) : FormattingPreferencesStoreProvider {
    override fun store(): FormattingPreferencesStore = fullBook
}
