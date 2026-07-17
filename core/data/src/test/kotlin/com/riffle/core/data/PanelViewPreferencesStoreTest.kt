package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PanelViewPreferencesStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newStore() = PanelViewPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("panel-view.preferences_pb") },
        ),
    )

    @Test fun `default panelViewOn is false`() = testScope.runTest {
        val store = newStore()
        assertEquals(false, store.state("book-1").first().panelViewOn)
    }

    @Test fun `setPanelViewOn round-trips`() = testScope.runTest {
        val store = newStore()
        store.setPanelViewOn("book-1", true)
        assertEquals(true, store.state("book-1").first().panelViewOn)
        store.setPanelViewOn("book-1", false)
        assertEquals(false, store.state("book-1").first().panelViewOn)
    }

    @Test fun `two bookIds are isolated`() = testScope.runTest {
        val store = newStore()
        store.setPanelViewOn("book-A", true)
        assertEquals(true, store.state("book-A").first().panelViewOn)
        assertEquals(false, store.state("book-B").first().panelViewOn)
    }
}
