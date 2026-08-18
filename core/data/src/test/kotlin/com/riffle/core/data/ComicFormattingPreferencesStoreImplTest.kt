package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
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
class ComicFormattingPreferencesStoreImplTest {

    @get:Rule val tmp = TemporaryFolder()
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newStore() = ComicFormattingPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("comic-formatting.preferences_pb") },
        ),
    )

    @Test fun `defaults are panelViewOn=false and panelOverflow=SPLIT`() = testScope.runTest {
        val prefs = newStore().preferences.first()
        assertEquals(false, prefs.panelViewOn)
        assertEquals(PanelOverflowBehavior.SPLIT, prefs.panelOverflow)
        assertEquals(250, prefs.panelAnimationSpeedMs)
    }

    @Test fun `panelViewOn round-trips`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelViewOn = true, panelOverflow = PanelOverflowBehavior.SPLIT))
        assertEquals(true, store.preferences.first().panelViewOn)
        store.update(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SPLIT))
        assertEquals(false, store.preferences.first().panelViewOn)
    }

    @Test fun `panelOverflow SMART_SPLIT round-trips`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SMART_SPLIT))
        assertEquals(PanelOverflowBehavior.SMART_SPLIT, store.preferences.first().panelOverflow)
    }

    @Test fun `panelOverflow SPLIT is stored as absent and reads back as SPLIT`() = testScope.runTest {
        val store = newStore()
        // Write SMART_SPLIT first so there is something to clear.
        store.update(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SMART_SPLIT))
        store.update(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.SPLIT))
        assertEquals(PanelOverflowBehavior.SPLIT, store.preferences.first().panelOverflow)
    }

    @Test fun `panelOverflow OFF round-trips`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelViewOn = false, panelOverflow = PanelOverflowBehavior.OFF))
        assertEquals(PanelOverflowBehavior.OFF, store.preferences.first().panelOverflow)
    }

    @Test fun `panelAnimationSpeedMs round-trips non-default value`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelAnimationSpeedMs = 400))
        assertEquals(400, store.preferences.first().panelAnimationSpeedMs)
    }

    @Test fun `panelAnimationSpeedMs 0 round-trips (disabled)`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelAnimationSpeedMs = 0))
        assertEquals(0, store.preferences.first().panelAnimationSpeedMs)
    }

    @Test fun `panelAnimationSpeedMs default 250 is stored as absent and reads back as 250`() = testScope.runTest {
        val store = newStore()
        store.update(ComicFormattingPreferences(panelAnimationSpeedMs = 400))
        store.update(ComicFormattingPreferences(panelAnimationSpeedMs = 250))
        assertEquals(250, store.preferences.first().panelAnimationSpeedMs)
    }

}
