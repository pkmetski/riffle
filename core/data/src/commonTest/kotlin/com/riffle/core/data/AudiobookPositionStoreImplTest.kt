package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.domain.SyncPositionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookPositionStoreImplTest {

    private class FakeClock(var ms: Long = 0L) : Clock {
        override fun nowMs() = ms
        override fun nowNs() = ms * 1_000_000L
    }

    private class FakeAudiobookPositionDao : AudiobookPositionDao {
        private val entities: MutableMap<Pair<String, String>, AudiobookPositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, AudiobookPositionEntity> get() = entities
        fun seed(entity: AudiobookPositionEntity) { entities[entity.sourceId to entity.itemId] = entity }
        override suspend fun upsert(entity: AudiobookPositionEntity) {
            entities[entity.sourceId to entity.itemId] = entity
        }
        override suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity? =
            entities[sourceId to itemId]
        override suspend fun acceptServerIfUnchanged(
            sourceId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean,
        ): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(positionSec = positionSec, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
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
    fun `save persists the seconds for the given item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", 123.5)
        assertEquals(123.5, dao.store["source-A" to "item-1"]?.positionSec ?: 0.0, 0.0001)
    }

    @Test
    fun `load returns the saved seconds`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 42.0, 1L))
        }
        val store = AudiobookPositionStoreImpl(dao, FakeClock())
        assertEquals(42.0, store.load("source-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao(), FakeClock())
        assertNull(store.load("source-A", "item-new"))
    }

    @Test
    fun `load returns null for a deleted row`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 42.0, 1L, deleted = true))
        }
        val store = AudiobookPositionStoreImpl(dao, FakeClock())
        assertNull(store.load("source-A", "item-1"))
    }

    @Test
    fun `save overwrites the previous position for the same source-item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", 10.0)
        store.save("source-A", "item-1", 99.0)
        assertEquals(99.0, store.load("source-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `save is idempotent — repeated save of the same seconds does not re-stamp`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val clock = FakeClock(1_000L)
        val store = AudiobookPositionStoreImpl(dao, clock)
        store.save("source-A", "item-1", 42.0)
        val stamp1 = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        clock.ms = 9_000L
        store.save("source-A", "item-1", 42.0) // same position
        val stamp2 = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        assertEquals(stamp1, stamp2, "repeated save of same position must not advance localUpdatedAt")
    }

    @Test
    fun `save stamps localUpdatedAt from the injected clock`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, FakeClock(1_700_000_000_000L))
        store.save("source-A", "item-1", 1.0)
        assertEquals(1_700_000_000_000L, dao.store["source-A" to "item-1"]?.localUpdatedAt)
    }

    @Test
    fun `save never regresses localUpdatedAt below an adopted future source stamp`() = runTest {
        // Same monotonic-timestamp-guard as the reading-position store (issue #528 guard).
        val dao = FakeAudiobookPositionDao()
        val futureSourceStamp = 1_700_120_000_000L
        dao.seed(AudiobookPositionEntity("source-A", "item-1", 10.0, futureSourceStamp, futureSourceStamp))
        val clock = FakeClock(1_700_000_000_000L) // device clock is 2 minutes behind
        val store = AudiobookPositionStoreImpl(dao, clock)

        store.save("source-A", "item-1", 99.0)

        val after = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        assertTrue(after > futureSourceStamp,
            "save() must advance localUpdatedAt past the adopted source stamp; was $after, source stamp $futureSourceStamp")
        assertEquals(99.0, store.load("source-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `positions for the same itemId on different sources are isolated`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, FakeClock(1_000L))
        store.save("source-A", "item-1", 10.0)
        store.save("source-B", "item-1", 99.0)
        assertEquals(10.0, store.load("source-A", "item-1")!!, 0.0001)
        assertEquals(99.0, store.load("source-B", "item-1")!!, 0.0001)
    }

    @Test
    fun `loadLocalUpdatedAt defaults to zero for a missing row`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao(), FakeClock())
        assertEquals(0L, store.loadLocalUpdatedAt("source-A", "item-new"))
    }

    // --- SyncPositionStore (ADR 0036) — exercised on iOS via issue #1065's server-sync wiring ---

    @Test
    fun `snapshot reflects the current position and both timestamps`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 42.0, localUpdatedAt = 500L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<Double> = AudiobookPositionStoreImpl(dao, FakeClock())
        val snap = store.snapshot("source-A", "item-1")
        assertEquals(42.0, snap.position!!, 0.0001)
        assertEquals(500L, snap.localUpdatedAt)
        assertEquals(100L, snap.lastSyncedAt)
    }

    @Test
    fun `acceptServerPosition overwrites the row and cleans both timestamps`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 10.0, localUpdatedAt = 100L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<Double> = AudiobookPositionStoreImpl(dao, FakeClock())
        val applied = store.acceptServerPosition("source-A", "item-1", 999.0, serverStamp = 900L, ifLocalUpdatedAt = 100L)
        assertTrue(applied)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals(999.0, e.positionSec, 0.0001)
        assertEquals(900L, e.localUpdatedAt)
        assertEquals(900L, e.lastSyncedAt)
    }

    @Test
    fun `acceptServerPosition is a no-op when localUpdatedAt advanced concurrently`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 55.0, localUpdatedAt = 200L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<Double> = AudiobookPositionStoreImpl(dao, FakeClock())
        val applied = store.acceptServerPosition("source-A", "item-1", 999.0, serverStamp = 900L, ifLocalUpdatedAt = 100L)
        assertTrue(!applied, "a concurrent local edit must not be clobbered by the server")
        assertEquals(55.0, dao.store["source-A" to "item-1"]?.positionSec ?: -1.0, 0.0001)
    }

    @Test
    fun `confirmPushed adopts the server stamp into both timestamps`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("source-A", "item-1", 10.0, localUpdatedAt = 500L, lastSyncedAt = 100L))
        }
        val store: SyncPositionStore<Double> = AudiobookPositionStoreImpl(dao, FakeClock())
        val applied = store.confirmPushed("source-A", "item-1", serverStamp = 500L, ifLocalUpdatedAt = 500L)
        assertTrue(applied)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals(500L, e.localUpdatedAt)
        assertEquals(500L, e.lastSyncedAt)
    }

    @Test
    fun `mirror writes the sibling position with the exact given timestamps`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store: SyncPositionStore<Double> = AudiobookPositionStoreImpl(dao, FakeClock())
        store.mirror("source-A", "item-1", 77.0, localUpdatedAt = 300L, lastSyncedAt = 200L)
        val e = dao.store["source-A" to "item-1"]!!
        assertEquals(77.0, e.positionSec, 0.0001)
        assertEquals(300L, e.localUpdatedAt)
        assertEquals(200L, e.lastSyncedAt)
    }
}
