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

    @Test fun `defaults - panelViewOn=false, no last position`() = testScope.runTest {
        val store = newStore()
        val state = store.state("book-1").first()
        assertEquals(false, state.panelViewOn)
        assertEquals(null, state.lastPageIndex)
        assertEquals(null, state.lastPanelIndex)
    }

    @Test fun `setPanelViewOn round-trips`() = testScope.runTest {
        val store = newStore()
        store.setPanelViewOn("book-1", true)
        assertEquals(true, store.state("book-1").first().panelViewOn)
        store.setPanelViewOn("book-1", false)
        assertEquals(false, store.state("book-1").first().panelViewOn)
    }

    @Test fun `rememberPositionForResume then read back both fields`() = testScope.runTest {
        val store = newStore()
        store.rememberPositionForResume("book-1", pageIndex = 12, panelIndex = 3)
        val state = store.state("book-1").first()
        assertEquals(12, state.lastPageIndex)
        assertEquals(3, state.lastPanelIndex)
    }

    @Test fun `panelIndexForPage returns 0 when current page differs from last`() = testScope.runTest {
        val store = newStore()
        store.rememberPositionForResume("book-1", pageIndex = 12, panelIndex = 3)
        val state = store.state("book-1").first()
        assertEquals(3, state.panelIndexForPage(12))
        assertEquals(0, state.panelIndexForPage(13))
    }

    @Test fun `clearPanelResume drops last position but preserves toggle`() = testScope.runTest {
        val store = newStore()
        store.setPanelViewOn("book-1", true)
        store.rememberPositionForResume("book-1", pageIndex = 7, panelIndex = 2)
        store.clearPanelResume("book-1")
        val state = store.state("book-1").first()
        assertEquals(true, state.panelViewOn)
        assertEquals(null, state.lastPageIndex)
        assertEquals(null, state.lastPanelIndex)
    }

    @Test fun `two bookIds are isolated`() = testScope.runTest {
        val store = newStore()
        store.setPanelViewOn("book-A", true)
        store.rememberPositionForResume("book-A", pageIndex = 5, panelIndex = 1)
        val stateA = store.state("book-A").first()
        val stateB = store.state("book-B").first()
        assertEquals(true, stateA.panelViewOn)
        assertEquals(false, stateB.panelViewOn)
        assertEquals(5, stateA.lastPageIndex)
        assertEquals(null, stateB.lastPageIndex)
    }
}
