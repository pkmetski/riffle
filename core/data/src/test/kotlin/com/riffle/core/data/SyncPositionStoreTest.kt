package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Room-backed [com.riffle.core.domain.SyncPositionStore] behaviour of the position stores
 * (ADR 0030): snapshot, the compare-and-clear conditional writes, and the regression that the
 * existing PositionStore writes preserve `lastSyncedAt` (so a local save marks the row dirty rather
 * than silently clearing the sync marker). Driven over faithful in-memory fake DAOs.
 */
class SyncPositionStoreTest {

    private class FakeReadingDao : ReadingPositionDao {
        val rows = mutableMapOf<Pair<String, String>, ReadingPositionEntity>()
        override suspend fun upsert(entity: ReadingPositionEntity) { rows[entity.serverId to entity.itemId] = entity }
        override suspend fun getByItemId(serverId: String, itemId: String) = rows[serverId to itemId]
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            rows[serverId to itemId]?.let { rows[serverId to itemId] = it.copy(localUpdatedAt = millis) }
        }
        override suspend fun acceptServerIfUnchanged(
            serverId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(cfi = position, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmPushedIfUnchanged(
            serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmInSyncIfUnchanged(
            serverId: String, itemId: String, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(lastSyncedAt = e.localUpdatedAt)
            return 1
        }
        override suspend fun dirtyForServer(serverId: String) =
            rows.values.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun serversWithDirtyRows() =
            rows.values.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()
    }

    // --- snapshot ---

    @Test
    fun `snapshot reflects position, localUpdatedAt and lastSyncedAt`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "cfi", 300L, 100L) }
        val snap = ReadingPositionStoreImpl(dao).snapshot("s", "i")
        assertEquals("cfi", snap.position)
        assertEquals(300L, snap.localUpdatedAt)
        assertEquals(100L, snap.lastSyncedAt)
    }

    @Test
    fun `snapshot of a missing row is empty and clean`() = runTest {
        val snap = ReadingPositionStoreImpl(FakeReadingDao()).snapshot("s", "i")
        assertEquals(null, snap.position)
        assertEquals(0L, snap.localUpdatedAt)
        assertEquals(0L, snap.lastSyncedAt)
    }

    // --- acceptServerPosition (server wins) ---

    @Test
    fun `acceptServerPosition persists position and clean stamps when unchanged`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "old", 100L, 100L) }
        val applied = ReadingPositionStoreImpl(dao)
            .acceptServerPosition("s", "i", "server", serverStamp = 200L, ifLocalUpdatedAt = 100L)
        assertTrue(applied)
        val row = dao.rows["s" to "i"]!!
        assertEquals("server", row.cfi)
        assertEquals(200L, row.localUpdatedAt)
        assertEquals(200L, row.lastSyncedAt) // clean
    }

    @Test
    fun `acceptServerPosition is refused and writes nothing when localUpdatedAt advanced`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "fresh-local", 150L, 100L) }
        val applied = ReadingPositionStoreImpl(dao)
            .acceptServerPosition("s", "i", "server", serverStamp = 200L, ifLocalUpdatedAt = 100L)
        assertFalse(applied)
        assertEquals("fresh-local", dao.rows["s" to "i"]!!.cfi) // not clobbered
        assertEquals(150L, dao.rows["s" to "i"]!!.localUpdatedAt)
    }

    @Test
    fun `acceptServerPosition creates the row when absent`() = runTest {
        val dao = FakeReadingDao()
        val applied = ReadingPositionStoreImpl(dao)
            .acceptServerPosition("s", "i", "server", serverStamp = 200L, ifLocalUpdatedAt = 0L)
        assertTrue(applied)
        val row = dao.rows["s" to "i"]!!
        assertEquals("server", row.cfi)
        assertEquals(200L, row.localUpdatedAt)
        assertEquals(200L, row.lastSyncedAt)
    }

    // --- confirmPushed (local win confirmed) ---

    @Test
    fun `confirmPushed adopts the server stamp into both timestamps when unchanged`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "cfi", 300L, 100L) }
        val applied = ReadingPositionStoreImpl(dao).confirmPushed("s", "i", serverStamp = 305L, ifLocalUpdatedAt = 300L)
        assertTrue(applied)
        val row = dao.rows["s" to "i"]!!
        assertEquals(305L, row.localUpdatedAt)
        assertEquals(305L, row.lastSyncedAt) // clean
        assertEquals("cfi", row.cfi) // position untouched
    }

    @Test
    fun `confirmPushed is refused when localUpdatedAt advanced mid-flight`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "cfi", 350L, 100L) }
        val applied = ReadingPositionStoreImpl(dao).confirmPushed("s", "i", serverStamp = 305L, ifLocalUpdatedAt = 300L)
        assertFalse(applied)
        assertEquals(350L, dao.rows["s" to "i"]!!.localUpdatedAt)
        assertEquals(100L, dao.rows["s" to "i"]!!.lastSyncedAt) // still dirty
    }

    // --- confirmInSync ---

    @Test
    fun `confirmInSync clears dirty by lifting lastSyncedAt to localUpdatedAt`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "cfi", 200L, 100L) }
        val applied = ReadingPositionStoreImpl(dao).confirmInSync("s", "i", ifLocalUpdatedAt = 200L)
        assertTrue(applied)
        assertEquals(200L, dao.rows["s" to "i"]!!.lastSyncedAt)
    }

    // --- PositionStore writes must preserve lastSyncedAt (regression) ---

    @Test
    fun `save preserves lastSyncedAt and marks the row dirty`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "old", 100L, 100L) }
        ReadingPositionStoreImpl(dao).save("s", "i", "new")
        val row = dao.rows["s" to "i"]!!
        assertEquals("new", row.cfi)
        assertEquals(100L, row.lastSyncedAt) // preserved, NOT reset to 0
        assertTrue("row should be dirty after a local save", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test
    fun `updateLocalTimestamp preserves lastSyncedAt`() = runTest {
        val dao = FakeReadingDao().apply { rows["s" to "i"] = ReadingPositionEntity("s", "i", "cfi", 100L, 100L) }
        ReadingPositionStoreImpl(dao).updateLocalTimestamp("s", "i", 250L)
        val row = dao.rows["s" to "i"]!!
        assertEquals(250L, row.localUpdatedAt)
        assertEquals(100L, row.lastSyncedAt)
    }

    // --- audio store (Double payload) parity ---

    private class FakeAudioDao : AudiobookPositionDao {
        val rows = mutableMapOf<Pair<String, String>, AudiobookPositionEntity>()
        override suspend fun upsert(entity: AudiobookPositionEntity) { rows[entity.serverId to entity.itemId] = entity }
        override suspend fun getByItemId(serverId: String, itemId: String) = rows[serverId to itemId]
        override suspend fun acceptServerIfUnchanged(
            serverId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(positionSec = positionSec, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmPushedIfUnchanged(
            serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmInSyncIfUnchanged(
            serverId: String, itemId: String, ifLocalUpdatedAt: Long,
        ): Int {
            val e = rows[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            rows[serverId to itemId] = e.copy(lastSyncedAt = e.localUpdatedAt)
            return 1
        }
        override suspend fun dirtyForServer(serverId: String) =
            rows.values.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun serversWithDirtyRows() =
            rows.values.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()
    }

    @Test
    fun `audio store reconciles over Double seconds`() = runTest {
        val dao = FakeAudioDao().apply { rows["s" to "i"] = AudiobookPositionEntity("s", "i", 10.0, 100L, 100L) }
        val store = AudiobookPositionStoreImpl(dao)

        assertEquals(10.0, store.snapshot("s", "i").position!!, 0.0001)
        assertTrue(store.acceptServerPosition("s", "i", 99.0, serverStamp = 200L, ifLocalUpdatedAt = 100L))
        val row = dao.rows["s" to "i"]!!
        assertEquals(99.0, row.positionSec, 0.0001)
        assertEquals(200L, row.lastSyncedAt)
    }

    @Test
    fun `audio save preserves lastSyncedAt`() = runTest {
        val dao = FakeAudioDao().apply { rows["s" to "i"] = AudiobookPositionEntity("s", "i", 10.0, 100L, 100L) }
        AudiobookPositionStoreImpl(dao).save("s", "i", 42.0)
        assertEquals(100L, dao.rows["s" to "i"]!!.lastSyncedAt)
    }
}
