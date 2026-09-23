package com.riffle.feature.reader

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.models.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the backward-compat position fallback added to fix the regression
 * introduced by PR #999: positions saved before #999 were keyed on `getActive()?.id` (ABS)
 * rather than the item's own sourceId, so an O'Reilly book read before #999 would open at the
 * cover on first open after the fix because its position row was stored under the wrong key.
 */
class PositionFallbackTest {

    private fun source(id: String) = Source(
        id = id,
        url = SourceUrl.parse("http://example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun `returns item-sourceId position when present`() = runTest {
        val store = MapPositionStore(
            mapOf("oreilly-src/book-1" to """{"locator":"correct"}"""),
        )
        val sourceRepo = ConstantSourceRepository(source("abs-src"))

        val result = loadPositionWithActiveFallback(store, sourceRepo, "oreilly-src", "book-1")

        assertEquals("""{"locator":"correct"}""", result)
    }

    @Test
    fun `falls back to active-source position when item-sourceId row is absent`() = runTest {
        // Simulates pre-PR#999 data: position saved under ABS, not O'Reilly
        val store = MapPositionStore(
            mapOf("abs-src/book-1" to """{"locator":"pre-999-saved"}"""),
        )
        val sourceRepo = ConstantSourceRepository(source("abs-src"))

        val result = loadPositionWithActiveFallback(store, sourceRepo, "oreilly-src", "book-1")

        assertEquals("""{"locator":"pre-999-saved"}""", result)
    }

    @Test
    fun `does NOT fall back when sourceId equals active source id`() = runTest {
        // ABS book opened while ABS is active: no fallback loop
        val store = MapPositionStore(emptyMap())
        val sourceRepo = ConstantSourceRepository(source("abs-src"))

        val result = loadPositionWithActiveFallback(store, sourceRepo, "abs-src", "book-1")

        assertNull(result)
    }

    @Test
    fun `returns null when both item-sourceId and active-source rows are absent`() = runTest {
        val store = MapPositionStore(emptyMap())
        val sourceRepo = ConstantSourceRepository(source("abs-src"))

        val result = loadPositionWithActiveFallback(store, sourceRepo, "oreilly-src", "missing-book")

        assertNull(result)
    }

    @Test
    fun `returns null when no active source`() = runTest {
        val store = MapPositionStore(emptyMap())
        val sourceRepo = ConstantSourceRepository(null)

        val result = loadPositionWithActiveFallback(store, sourceRepo, "oreilly-src", "book-1")

        assertNull(result)
    }

    // --- fakes ---

    private class MapPositionStore(private val data: Map<String, String>) : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? =
            data["$sourceId/$itemId"]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private class ConstantSourceRepository(private val active: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}
