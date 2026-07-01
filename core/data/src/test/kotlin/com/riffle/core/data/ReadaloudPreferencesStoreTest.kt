package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.HighlightColor
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
        for (color in HighlightColor.entries) {
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
        assertEquals(HighlightColor.BLUE, store.preferences.first().highlightColor)
    }

    // The fourth swatch was PINK before it became RED, and PURPLE was dropped from the palette
    // entirely. Users who had either selected must still get a usable app on next launch — the
    // unknown-name fallback picks the default (BLUE) so the reader has a valid colour to render
    // and the picker shows a real selection. The persisted string stays "PINK"/"PURPLE" until
    // the user re-picks, which is fine: every subsequent read takes the same fallback path.
    @Test
    fun `legacy PINK stored value falls back to default BLUE`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs_pink.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("highlight_color")] = "PINK" }
        val store = ReadaloudPreferencesStore(rawStore)
        assertEquals(HighlightColor.BLUE, store.preferences.first().highlightColor)
    }

    @Test
    fun `legacy PURPLE stored value falls back to default BLUE`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs_purple.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("highlight_color")] = "PURPLE" }
        val store = ReadaloudPreferencesStore(rawStore)
        assertEquals(HighlightColor.BLUE, store.preferences.first().highlightColor)
    }

    // After a legacy value falls back and the user re-picks, the new choice must persist —
    // guards against a codec that reads-through but forgets to overwrite on write.
    @Test
    fun `re-picking after a legacy fallback persists the new choice`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs_repick.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("highlight_color")] = "PURPLE" }
        val store = ReadaloudPreferencesStore(rawStore)
        assertEquals(HighlightColor.BLUE, store.preferences.first().highlightColor)

        store.update(ReadaloudPreferences(highlightColor = HighlightColor.GREEN))
        assertEquals(HighlightColor.GREEN, store.preferences.first().highlightColor)

        // A fresh store instance reading the same DataStore must see GREEN, not fall back to BLUE.
        val reopened = ReadaloudPreferencesStore(rawStore)
        assertEquals(HighlightColor.GREEN, reopened.preferences.first().highlightColor)
    }
}
