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
    }

    @Test
    fun `save persists the seconds for the given item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 123.5)
        assertEquals(123.5, dao.store["server-A" to "item-1"]?.positionSec ?: 0.0, 0.0001)
    }

    @Test
    fun `load returns the saved seconds`() = runTest {
        val dao = FakeAudiobookPositionDao().also {
            it.seed(AudiobookPositionEntity("server-A", "item-1", 42.0, 1L))
        }
        val store = AudiobookPositionStoreImpl(dao)
        assertEquals(42.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao())
        assertNull(store.load("server-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same server-item`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 10.0)
        store.save("server-A", "item-1", 99.0)
        assertEquals(99.0, store.load("server-A", "item-1")!!, 0.0001)
    }

    @Test
    fun `save stamps localUpdatedAt with current time`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        val before = System.currentTimeMillis()
        store.save("server-A", "item-1", 1.0)
        val after = System.currentTimeMillis()
        val ts = dao.store["server-A" to "item-1"]?.localUpdatedAt ?: 0L
        assert(ts in before..after) { "Expected timestamp in [$before..$after] but was $ts" }
    }

    @Test
    fun `loadLocalUpdatedAt defaults to zero for a missing row`() = runTest {
        val store = AudiobookPositionStoreImpl(FakeAudiobookPositionDao())
        assertEquals(0L, store.loadLocalUpdatedAt("server-A", "item-new"))
    }

    @Test
    fun `updateLocalTimestamp creates a row when none exists so it is not silently dropped`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.updateLocalTimestamp("server-A", "item-1", 555L)
        assertEquals(555L, store.loadLocalUpdatedAt("server-A", "item-1"))
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeAudiobookPositionDao()
        val store = AudiobookPositionStoreImpl(dao)
        store.save("server-A", "item-1", 10.0)
        store.save("server-B", "item-1", 99.0)
        assertEquals(10.0, store.load("server-A", "item-1")!!, 0.0001)
        assertEquals(99.0, store.load("server-B", "item-1")!!, 0.0001)
    }
}
