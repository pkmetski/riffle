package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.SyncPositionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingPositionStoreImplTest {

    private class FakeClock(var ms: Long = 0L) : Clock {
        override fun nowMs() = ms
        override fun nowNs() = ms * 1_000_000L
    }

    private class FakeReadingPositionDao : ReadingPositionDao {
        private val entities: MutableMap<Pair<String, String>, ReadingPositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, ReadingPositionEntity> get() = entities
        fun seed(entity: ReadingPositionEntity) { entities[entity.sourceId to entity.itemId] = entity }
        override suspend fun upsert(entity: ReadingPositionEntity) {
            entities[entity.sourceId to entity.itemId] = entity
        }
        override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? =
            entities[sourceId to itemId]
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            entities[sourceId to itemId]?.let { entities[sourceId to itemId] = it.copy(localUpdatedAt = millis) }
        }
        override suspend fun acceptServerIfUnchanged(
            sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean,
        ): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(cfi = position, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) {}
        override suspend fun acceptServerDeletionIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int = 0
        override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(lastSyncedAt = e.localUpdatedAt)
            return 1
        }
        override suspend fun dirtyForSource(sourceId: String) =
            entities.values.filter { it.sourceId == sourceId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun sourcesWithDirtyRows() =
            entities.values.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.sourceId }.distinct()
        override suspend fun allForSource(sourceId: String) =
            entities.values.filter { it.sourceId == sourceId }
    }

    @Test
    fun `save persists the CFI for the given item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")
        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", dao.store["source-A" to "item-1"]?.cfi)
    }

    @Test
    fun `load returns the saved CFI`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao, FakeClock())
        assertEquals("epubcfi(/6/2!/4/1:42)", store.load("source-A", "item-1"))
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = ReadingPositionStoreImpl(FakeReadingPositionDao(), FakeClock())
        assertNull(store.load("source-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same source-item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:99)")
        assertEquals("epubcfi(/6/2!/4/1:99)", store.load("source-A", "item-1"))
    }

    @Test
    fun `save is idempotent — repeated save of the same CFI does not re-stamp`() = runTest {
        val dao = FakeReadingPositionDao()
        val clock = FakeClock(1_000L)
        val store = ReadingPositionStoreImpl(dao, clock)
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        val stamp1 = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        clock.ms = 9_000L
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)") // same CFI
        val stamp2 = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        assertEquals(stamp1, stamp2, "repeated save of same CFI must not advance localUpdatedAt")
    }

    @Test
    fun `save never regresses localUpdatedAt below an adopted future source stamp`() = runTest {
        // When ABS's clock is ahead of the device, the sync cycle adopts a future source stamp as
        // localUpdatedAt. A subsequent save() using raw now() must not silently lower localUpdatedAt
        // back under the source stamp — that would make every subsequent sync cycle conclude
        // source-wins and yank the reader to the older server position (issue #528).
        val dao = FakeReadingPositionDao()
        val futureSourceStamp = 1_700_120_000_000L
        dao.seed(ReadingPositionEntity("source-A", "item-1", "old", futureSourceStamp, futureSourceStamp))
        val clock = FakeClock(1_700_000_000_000L) // device clock is 2 minutes behind
        val store = ReadingPositionStoreImpl(dao, clock)

        store.save("source-A", "item-1", "fresh")

        val after = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        assertTrue(after > futureSourceStamp,
            "save() must advance localUpdatedAt past the adopted source stamp; was $after, source stamp $futureSourceStamp")
        assertEquals("fresh", store.load("source-A", "item-1"))
    }

    @Test
    fun `positions for the same itemId on different sources are isolated`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("source-B", "item-1", "epubcfi(/6/8!/4/1:99)")
        assertEquals("epubcfi(/6/2!/4/1:10)", store.load("source-A", "item-1"))
        assertEquals("epubcfi(/6/8!/4/1:99)", store.load("source-B", "item-1"))
        assertNotEquals(store.load("source-A", "item-1"), store.load("source-B", "item-1"))
    }

    @Test
    fun `load on a different source returns null even when itemId is saved elsewhere`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao, FakeClock())
        assertNull(store.load("source-B", "item-1"))
    }

    @Test
    fun `load returns null for a deleted row`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "epubcfi(/6/2!/4/1:42)", deleted = true))
        }
        val store = ReadingPositionStoreImpl(dao, FakeClock())
        assertNull(store.load("source-A", "item-1"))
    }

    @Test
    fun `save stamps localUpdatedAt from the injected clock`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, FakeClock(1_700_000_000_000L))
        store.save("source-A", "item-1", "cfi")
        assertEquals(1_700_000_000_000L, dao.store["source-A" to "item-1"]?.localUpdatedAt)
    }

    // --- SyncPositionStore (ADR 0036) — exercised on iOS via issue #1065's server-sync wiring ---

    @Test
    fun `snapshot reflects the current position and both timestamps`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "cfi", localUpdatedAt = 500L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        val snap = store.snapshot("source-A", "item-1")
        assertEquals("cfi", snap.position)
        assertEquals(500L, snap.localUpdatedAt)
        assertEquals(100L, snap.lastSyncedAt)
    }

    @Test
    fun `acceptServerPosition overwrites the row and cleans both timestamps`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "old-cfi", localUpdatedAt = 100L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        val applied = store.acceptServerPosition("source-A", "item-1", "server-cfi", serverStamp = 900L, ifLocalUpdatedAt = 100L)
        assertTrue(applied)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals("server-cfi", e.cfi)
        assertEquals(900L, e.localUpdatedAt)
        assertEquals(900L, e.lastSyncedAt)
    }

    @Test
    fun `acceptServerPosition is a no-op when localUpdatedAt advanced concurrently`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "fresh-local-cfi", localUpdatedAt = 200L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        val applied = store.acceptServerPosition("source-A", "item-1", "server-cfi", serverStamp = 900L, ifLocalUpdatedAt = 100L)
        assertTrue(!applied, "a concurrent local edit must not be clobbered by the server")
        assertEquals("fresh-local-cfi", dao.store["source-A" to "item-1"]?.cfi)
    }

    @Test
    fun `confirmPushed adopts the server stamp into both timestamps`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "cfi", localUpdatedAt = 500L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        val applied = store.confirmPushed("source-A", "item-1", serverStamp = 500L, ifLocalUpdatedAt = 500L)
        assertTrue(applied)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals(500L, e.localUpdatedAt)
        assertEquals(500L, e.lastSyncedAt)
    }

    @Test
    fun `confirmInSync marks a dirty row clean without changing the position`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "cfi", localUpdatedAt = 500L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        val applied = store.confirmInSync("source-A", "item-1", ifLocalUpdatedAt = 500L)
        assertTrue(applied)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals("cfi", e.cfi)
        assertEquals(500L, e.lastSyncedAt)
    }

    @Test
    fun `mirror writes the sibling position with the exact given timestamps`() = runTest {
        val dao = FakeReadingPositionDao()
        val store: SyncPositionStore<String> = ReadingPositionStoreImpl(dao, FakeClock())
        store.mirror("source-A", "item-1", "mirrored-cfi", localUpdatedAt = 300L, lastSyncedAt = 200L)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals("mirrored-cfi", e.cfi)
        assertEquals(300L, e.localUpdatedAt)
        assertEquals(200L, e.lastSyncedAt)
    }
}
