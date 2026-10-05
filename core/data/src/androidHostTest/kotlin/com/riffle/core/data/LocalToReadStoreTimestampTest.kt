package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.common.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalToReadStoreTimestampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    /** Real-time clock so the timestamp assertions (before..after) remain valid. */
    private val realClock = object : Clock {
        override fun nowMs() = System.currentTimeMillis()
        override fun nowNs() = System.nanoTime()
    }

    private fun buildStore() = LocalToReadStoreImpl(
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("to_read_ts_test_${System.nanoTime()}.preferences_pb") },
        ),
        clock = realClock,
    )

    @Test
    fun lastUpdateMs_returns0_before_any_write() = testScope.runTest {
        val store = buildStore()
        assertEquals(0L, store.lastUpdateMs("lib1"))
    }

    @Test
    fun add_bumps_lastUpdateMs() = testScope.runTest {
        val store = buildStore()
        val before = System.currentTimeMillis()
        store.add("lib1", "item1")
        val after = System.currentTimeMillis()
        val ts = store.lastUpdateMs("lib1")
        assertTrue("timestamp $ts not in [$before, $after]", ts in before..after)
    }

    @Test
    fun remove_bumps_lastUpdateMs() = testScope.runTest {
        val store = buildStore()
        store.add("lib1", "item1")
        val before = System.currentTimeMillis()
        store.remove("lib1", "item1")
        val after = System.currentTimeMillis()
        val ts = store.lastUpdateMs("lib1")
        assertTrue("timestamp $ts not in [$before, $after]", ts in before..after)
    }

    @Test
    fun setAll_replaces_items_and_stores_timestamp() = testScope.runTest {
        val store = buildStore()
        store.add("lib1", "old")
        store.setAll("lib1", setOf("new1", "new2"), 123_000L)
        assertEquals(setOf("new1", "new2"), store.observeItemIds("lib1").first())
        assertEquals(123_000L, store.lastUpdateMs("lib1"))
    }

    @Test
    fun lastUpdateMs_is_isolated_per_libraryId() = testScope.runTest {
        val store = buildStore()
        store.add("libA", "x")
        assertEquals(0L, store.lastUpdateMs("libB"))
    }
}
