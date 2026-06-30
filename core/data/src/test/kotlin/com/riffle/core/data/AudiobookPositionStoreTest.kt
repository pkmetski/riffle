package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookPositionStoreTest {

    private class FakeAudiobookPositionDao : AudiobookPositionDao {
        private val entities: MutableMap<Pair<String, String>, AudiobookPositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, AudiobookPositionEntity> get() = entities
        fun seed(entity: AudiobookPositionEntity) { entities[entity.serverId to entity.itemId] = entity }
        override suspend fun upsert(entity: AudiobookPositionEntity) {
            entities[entity.serverId to entity.itemId] = entity
        }
        override suspend fun getByItemId(serverId: String, itemId: String): AudiobookPositionEntity? =
            entities[serverId to itemId]
        override suspend fun acceptServerIfUnchanged(
            serverId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[serverId to itemId] = e.copy(positionSec = positionSec, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmPushedIfUnchanged(
            serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[serverId to itemId] = e.copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmInSyncIfUnchanged(serverId: String, itemId: String, ifLocalUpdatedAt: Long): Int {
            val e = entities[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[serverId to itemId] = e.copy(lastSyncedAt = e.localUpdatedAt)
            return 1
        }
        override suspend fun dirtyForServer(serverId: String) =
            entities.values.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun serversWithDirtyRows() =
            entities.values.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()
    }

    @Test
    fun `save persists the seconds for the given item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.save("server-A", "item-1", 123.5)
        assertEquals(123.5, dao.store["server-A" to "item-1"]?.positionSec ?: 0.0, 0.0001)
    }

    @Test
    fun `load returns the saved seconds`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("server-A", "item-1", 42.0, 1L))
        }
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertEquals(42.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao(), com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertNull(store.load("server-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same server-item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.save("server-A", "item-1", 10.0)
        store.save("server-A", "item-1", 99.0)
        assertEquals(99.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `save stamps localUpdatedAt from the injected clock`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(initialMs = 1_700_000_000_000L))
        store.save("server-A", "item-1", 1.0)
        assertEquals(1_700_000_000_000L, dao.store["server-A" to "item-1"]?.localUpdatedAt)
    }

    @Test
    fun `loadLocalUpdatedAt defaults to zero for a missing row`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao(), com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertEquals(0L, store.loadLocalUpdatedAt("server-A", "item-new"))
    }

    @Test
    fun `updateLocalTimestamp creates a row when none exists so it is not silently dropped`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.updateLocalTimestamp("server-A", "item-1", 555L)
        assertEquals(555L, store.loadLocalUpdatedAt("server-A", "item-1"))
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.save("server-A", "item-1", 10.0)
        store.save("server-B", "item-1", 99.0)
        assertEquals(10.0, store.load("server-A", "item-1")!!, 0.0001)
        assertEquals(99.0, store.load("server-B", "item-1")!!, 0.0001)
    }
}
