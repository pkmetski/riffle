package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration test: DataStore write → observable Flow emits updated value.
 * Uses a real DataStore instance (file-backed) — no mocking.
 */
class FormattingPreferencesIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `DataStore write emits updated value through Flow`() = testScope.runTest {
        val store = FormattingPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmp.newFile("integration.preferences_pb") },
            )
        )

        store.update(FormattingPreferences(theme = ReaderTheme.Dark, fontSize = 1.4f))

        val emitted = store.preferences.first()
        assertEquals(ReaderTheme.Dark, emitted.theme)
        assertEquals(1.4f, emitted.fontSize)
    }
}
