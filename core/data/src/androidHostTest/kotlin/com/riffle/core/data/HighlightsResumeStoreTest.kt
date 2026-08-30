package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightsResumeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = HighlightsResumeStore(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("highlights_resume_prefs.preferences_pb") },
        )
    )

    @Test
    fun setThenGet_roundtripsTheValue() = testScope.runTest {
        val store = buildStore()
        store.setLastHighlightId("S1", "B1", "h1")
        assertEquals("h1", store.lastHighlightId("S1", "B1"))
    }

    @Test
    fun differentKeysAreIsolated() = testScope.runTest {
        val store = buildStore()
        store.setLastHighlightId("S1", "B1", "h1")
        store.setLastHighlightId("S1", "B2", "h2")
        assertEquals("h1", store.lastHighlightId("S1", "B1"))
        assertEquals("h2", store.lastHighlightId("S1", "B2"))
    }

    @Test
    fun unsetKeyReturnsNull() = testScope.runTest {
        val store = buildStore()
        assertNull(store.lastHighlightId("S1", "unknown"))
    }
}
