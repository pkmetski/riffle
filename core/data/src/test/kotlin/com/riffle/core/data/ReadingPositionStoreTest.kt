package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionStoreTest {

    private class FakeReadingPositionDao : ReadingPositionDao {
        private val entities: MutableMap<Pair<String, String>, ReadingPositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, ReadingPositionEntity> get() = entities
        fun seed(entity: ReadingPositionEntity) { entities[entity.serverId to entity.itemId] = entity }
        override suspend fun upsert(entity: ReadingPositionEntity) {
            entities[entity.serverId to entity.itemId] = entity
        }
        override suspend fun getByItemId(serverId: String, itemId: String): ReadingPositionEntity? =
            entities[serverId to itemId]
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            entities[serverId to itemId]?.let { entities[serverId to itemId] = it.copy(localUpdatedAt = millis) }
        }
        override suspend fun acceptServerIfUnchanged(
            serverId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[serverId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[serverId to itemId] = e.copy(cfi = position, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
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
    fun `save persists the CFI for the given item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        store.save("server-A", "item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")
        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", dao.store["server-A" to "item-1"]?.cfi)
    }

    @Test
    fun `load returns the saved CFI`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("server-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao)
        assertEquals("epubcfi(/6/2!/4/1:42)", store.load("server-A", "item-1"))
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = ReadingPositionStoreImpl(FakeReadingPositionDao())
        assertNull(store.load("server-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same server-item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        store.save("server-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("server-A", "item-1", "epubcfi(/6/2!/4/1:99)")
        assertEquals("epubcfi(/6/2!/4/1:99)", store.load("server-A", "item-1"))
    }

    @Test
    fun `save stamps localUpdatedAt with current time`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        val before = System.currentTimeMillis()
        store.save("server-A", "item-1", "cfi")
        val after = System.currentTimeMillis()
        val ts = dao.store["server-A" to "item-1"]?.localUpdatedAt ?: 0L
        assert(ts in before..after) { "Expected timestamp in [$before..$after] but was $ts" }
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)

        store.save("server-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("server-B", "item-1", "epubcfi(/6/8!/4/1:99)")

        assertEquals("epubcfi(/6/2!/4/1:10)", store.load("server-A", "item-1"))
        assertEquals("epubcfi(/6/8!/4/1:99)", store.load("server-B", "item-1"))
        assertNotEquals(store.load("server-A", "item-1"), store.load("server-B", "item-1"))
    }

    @Test
    fun `load on a different server returns null even when itemId is saved elsewhere`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("server-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao)
        assertNull(store.load("server-B", "item-1"))
    }
}
