package com.riffle.core.data

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins that FormattingPreferencesStoreProviderImpl returns the same store instance every time
 * so that the elided (annotations) view shares reading-mode and theme with the full-book reader.
 * Revert to a multi-instance provider and this assertion flips red.
 */
class FormattingPreferencesStoreProviderImplTest {

    private class StubStore : FormattingPreferencesStore {
        override val preferences: Flow<FormattingPreferences> = MutableStateFlow(FormattingPreferences())
        override suspend fun update(preferences: FormattingPreferences) = Unit
        override suspend fun setCadencePlatformSupported(supported: Boolean) = Unit
    }

    @Test
    fun `store returns the shared fullBook store instance`() {
        val fullBook = StubStore()
        val provider = FormattingPreferencesStoreProviderImpl(fullBook)
        assertSame(
            "store() must return the shared fullBook instance so global settings reach both reader modes",
            fullBook,
            provider.store(),
        )
    }
}
