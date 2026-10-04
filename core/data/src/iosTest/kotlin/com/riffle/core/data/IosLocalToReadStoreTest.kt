package com.riffle.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosLocalToReadStoreTest {

    // Each test uses a fresh suffix to isolate NSUserDefaults keys.
    private fun store(suffix: String = "test_${kotlin.random.Random.nextLong()}") =
        IosLocalToReadStore(suiteName = "riffle.toread.$suffix")

    @Test
    fun observeItemIds_emitsEmpty_before_any_add() = runTest {
        val s = store()
        assertEquals(emptySet(), s.observeItemIds("lib1").first())
    }

    @Test
    fun add_persists_and_observe_emits() = runTest {
        val s = store()
        s.add("lib1", "item1")
        assertTrue(s.observeItemIds("lib1").first().contains("item1"))
    }

    @Test
    fun remove_removes_item() = runTest {
        val s = store()
        s.add("lib1", "item1")
        s.add("lib1", "item2")
        s.remove("lib1", "item1")
        val ids = s.observeItemIds("lib1").first()
        assertFalse(ids.contains("item1"))
        assertTrue(ids.contains("item2"))
    }

    @Test
    fun isInToRead_returns_correct_result() = runTest {
        val s = store()
        s.add("lib1", "item1")
        assertTrue(s.isInToRead("lib1", "item1"))
        assertFalse(s.isInToRead("lib1", "item2"))
    }

    @Test
    fun lastUpdateMs_returns0_before_any_write() = runTest {
        val s = store()
        assertEquals(0L, s.lastUpdateMs("lib1"))
    }

    @Test
    fun add_bumps_lastUpdateMs() = runTest {
        val s = store()
        val before = currentTimeMs()
        s.add("lib1", "item1")
        val ts = s.lastUpdateMs("lib1")
        val after = currentTimeMs()
        assertTrue(ts in before..after, "ts=$ts not in [$before, $after]")
    }

    @Test
    fun setAll_replaces_items_and_stores_timestamp() = runTest {
        val s = store()
        s.add("lib1", "old")
        s.setAll("lib1", setOf("new1", "new2"), 999_000L)
        assertEquals(setOf("new1", "new2"), s.observeItemIds("lib1").first())
        assertEquals(999_000L, s.lastUpdateMs("lib1"))
    }

    @Test
    fun keys_are_isolated_per_libraryId() = runTest {
        val s = store()
        s.add("libA", "x")
        assertEquals(emptySet(), s.observeItemIds("libB").first())
        assertEquals(0L, s.lastUpdateMs("libB"))
    }

    private fun currentTimeMs(): Long =
        (NSDate().timeIntervalSince1970 * 1000).toLong()
}
