package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.HighlightColor
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
class HighlightColorPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = HighlightColorPreferencesStore(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("highlight_color_prefs.preferences_pb") },
        )
    )

    // First-run default matches AnnotationStore.DEFAULT_COLOR ("yellow") so users who never touch
    // the picker see the same colour they got before this feature landed.
    @Test
    fun `default is YELLOW when DataStore is empty`() = testScope.runTest {
        assertEquals(HighlightColor.YELLOW, buildStore().lastUsedColor.first())
    }

    @Test
    fun `each highlight color round-trips through DataStore`() = testScope.runTest {
        val store = buildStore()
        for (color in HighlightColor.entries) {
            store.setLastUsedColor(color)
            assertEquals(color, store.lastUsedColor.first())
        }
    }

    // A future palette rev that drops or renames a colour must not leave the store returning
    // "unknown" — the codec falls back to the default so the picker still has a valid selection.
    @Test
    fun `unrecognized stored value falls back to YELLOW`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("highlight_color_prefs_fallback.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("last_used_highlight_color")] = "NOT_A_COLOR" }
        val store = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.YELLOW, store.lastUsedColor.first())
    }

    // After the user re-picks following a fallback, the new choice must persist across store
    // instances — guards against a codec that reads-through but forgets to overwrite on write.
    @Test
    fun `re-picking after a fallback persists the new choice`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("highlight_color_prefs_repick.preferences_pb") },
        )
        rawStore.edit { it[stringPreferencesKey("last_used_highlight_color")] = "NOT_A_COLOR" }
        val store = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.YELLOW, store.lastUsedColor.first())

        store.setLastUsedColor(HighlightColor.GREEN)
        assertEquals(HighlightColor.GREEN, store.lastUsedColor.first())

        val reopened = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.GREEN, reopened.lastUsedColor.first())
    }
}
