package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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

    private fun buildStore(fileName: String = "highlight_color_prefs.preferences_pb") =
        HighlightColorPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmp.newFile(fileName) },
            )
        )

    // A book the user has never picked a colour on falls back to HighlightColor.DEFAULT — the
    // first entry in the palette. Reverting the fallback in the factory (or defaulting to the
    // last global pick instead of the palette default) flips this assertion red.
    @Test
    fun `default is DEFAULT for a book with no stored value`() = testScope.runTest {
        assertEquals(HighlightColor.DEFAULT, buildStore().lastUsedColor("srv1", "book1").first())
    }

    @Test
    fun `each highlight color round-trips through DataStore per book`() = testScope.runTest {
        val store = buildStore()
        for (color in HighlightColor.entries) {
            store.setLastUsedColor("srv1", "book1", color)
            assertEquals(color, store.lastUsedColor("srv1", "book1").first())
        }
    }

    // Two books' last-used colours are independent — picking on one MUST NOT change the other's
    // remembered colour. Reverting the per-book keying (falling back to a single global key)
    // would make bookB inherit bookA's pick and this test would fail.
    @Test
    fun `each book remembers its own last-used colour independently`() = testScope.runTest {
        val store = buildStore()
        store.setLastUsedColor("srv1", "bookA", HighlightColor.GREEN)
        store.setLastUsedColor("srv1", "bookB", HighlightColor.BLUE)

        assertEquals(HighlightColor.GREEN, store.lastUsedColor("srv1", "bookA").first())
        assertEquals(HighlightColor.BLUE, store.lastUsedColor("srv1", "bookB").first())
        // A third, never-picked book still falls back to DEFAULT — the picks on bookA/bookB
        // must not leak into a book that was never touched.
        assertEquals(HighlightColor.DEFAULT, store.lastUsedColor("srv1", "bookC").first())
    }

    // Same itemId on two different servers must not collide — source-scoping in the key is what
    // guarantees this. Dropping sourceId from the key would make srv2's bookX inherit srv1's pick.
    @Test
    fun `same itemId on different servers is independent`() = testScope.runTest {
        val store = buildStore()
        store.setLastUsedColor("srv1", "bookX", HighlightColor.RED)
        assertEquals(HighlightColor.DEFAULT, store.lastUsedColor("srv2", "bookX").first())
    }

    // A future palette rev that drops or renames a colour must not leave the store returning
    // an unknown value — it falls back to DEFAULT so the picker still has a valid selection.
    @Test
    fun `unrecognized stored value falls back to DEFAULT`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("highlight_color_prefs_fallback.preferences_pb") },
        )
        rawStore.edit { it[highlightColorPrefKey("srv1", "book1")] = "NOT_A_COLOR" }
        val store = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.DEFAULT, store.lastUsedColor("srv1", "book1").first())
    }

    // After the user re-picks following a fallback, the new choice must persist across store
    // instances — guards against a codec that reads-through but forgets to overwrite on write.
    @Test
    fun `re-picking after a fallback persists the new choice`() = testScope.runTest {
        val rawStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("highlight_color_prefs_repick.preferences_pb") },
        )
        rawStore.edit { it[highlightColorPrefKey("srv1", "book1")] = "NOT_A_COLOR" }
        val store = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.DEFAULT, store.lastUsedColor("srv1", "book1").first())

        store.setLastUsedColor("srv1", "book1", HighlightColor.GREEN)
        assertEquals(HighlightColor.GREEN, store.lastUsedColor("srv1", "book1").first())

        val reopened = HighlightColorPreferencesStore(rawStore)
        assertEquals(HighlightColor.GREEN, reopened.lastUsedColor("srv1", "book1").first())
    }
}
