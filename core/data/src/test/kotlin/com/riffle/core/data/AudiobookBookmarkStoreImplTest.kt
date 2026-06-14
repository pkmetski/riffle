package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBookmarkStoreImplTest {

    private class FakeDao : AudiobookBookmarkDao {
        val rows = MutableStateFlow<List<AudiobookBookmarkEntity>>(emptyList())
        override suspend fun upsert(entity: AudiobookBookmarkEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }
        override fun observeForItem(serverId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list -> list.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }.sortedBy { it.positionSec } }
        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun allForItem(serverId: String, itemId: String) =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId }
        override suspend fun dirtyForServer(serverId: String) =
            rows.value.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun serversWithDirtyRows() =
            rows.value.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()
        override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
        override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long) = 0
        override suspend fun hardDelete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    @Test fun addCreatesDirtyRow() = runTest {
        val dao = FakeDao(); val store = AudiobookBookmarkStoreImpl(dao)
        val id = store.add("s1", "i1", 765.0, "The Egg · 12:45", now = 1000L)
        val row = dao.getById(id)!!
        assertEquals(765.0, row.positionSec, 0.0001)
        assertEquals("The Egg · 12:45", row.title)
        assertEquals(1000L, row.createdAt)
        assertTrue("new row must be dirty", row.localUpdatedAt > row.lastSyncedAt)
        assertEquals(false, row.deleted)
    }

    @Test fun renameBumpsDirtyStamp() = runTest {
        val dao = FakeDao(); val store = AudiobookBookmarkStoreImpl(dao)
        val id = store.add("s1", "i1", 10.0, "old", now = 1000L)
        store.rename(id, "new", now = 2000L)
        val row = dao.getById(id)!!
        assertEquals("new", row.title); assertEquals(2000L, row.localUpdatedAt)
        assertTrue(row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun deleteTombstonesNotHardRemoves() = runTest {
        val dao = FakeDao(); val store = AudiobookBookmarkStoreImpl(dao)
        val id = store.add("s1", "i1", 10.0, "x", now = 1000L)
        store.delete(id, now = 3000L)
        val row = dao.getById(id)!!
        assertEquals(true, row.deleted); assertEquals(3000L, row.localUpdatedAt)
        assertTrue("tombstone must be dirty", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun observeMapsToDomain() = runTest {
        val dao = FakeDao(); val store = AudiobookBookmarkStoreImpl(dao)
        store.add("s1", "i1", 10.0, "a", now = 1000L)
        val list = store.observe("s1", "i1").first()
        assertEquals(1, list.size); assertEquals("a", list[0].title)
    }
}
