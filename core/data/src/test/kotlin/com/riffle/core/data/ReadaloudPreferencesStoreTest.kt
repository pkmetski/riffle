package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
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
class ReadaloudPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ReadaloudPreferencesStore(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default preferences returned when DataStore is empty`() = testScope.runTest {
        assertEquals(ReadaloudPreferences(), buildStore().preferences.first())
    }

    @Test
    fun `each highlight color round-trips through DataStore`() = testScope.runTest {
        val store = buildStore()
        for (color in ReadaloudHighlightColor.entries) {
            store.update(ReadaloudPreferences(highlightColor = color))
            assertEquals(color, store.preferences.first().highlightColor)
        }
    }

    @Test
    fun `unrecognized stored value falls back to BLUE`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs_fallback.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("highlight_color")] = "NOT_A_COLOR" }
        val store = ReadaloudPreferencesStore(rawStore)
        assertEquals(ReadaloudHighlightColor.BLUE, store.preferences.first().highlightColor)
    }
}
