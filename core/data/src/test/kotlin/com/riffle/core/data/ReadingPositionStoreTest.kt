package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionStoreTest {

    private class FakeReadingPositionDao : ReadingPositionDao {
        private val entities: MutableMap<String, ReadingPositionEntity> = mutableMapOf()
        val store: Map<String, ReadingPositionEntity> get() = entities
        fun seed(entity: ReadingPositionEntity) { entities[entity.itemId] = entity }
        override suspend fun upsert(entity: ReadingPositionEntity) { entities[entity.itemId] = entity }
        override suspend fun getByItemId(itemId: String): ReadingPositionEntity? = entities[itemId]
        override suspend fun updateLocalTimestamp(itemId: String, millis: Long) {
            entities[itemId]?.let { entities[itemId] = it.copy(localUpdatedAt = millis) }
        }
    }

    @Test
    fun `save persists the CFI for the given item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        store.save("item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")
        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", dao.store["item-1"]?.cfi)
    }

    @Test
    fun `load returns the saved CFI`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao)
        assertEquals("epubcfi(/6/2!/4/1:42)", store.load("item-1"))
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = ReadingPositionStoreImpl(FakeReadingPositionDao())
        assertNull(store.load("item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        store.save("item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("item-1", "epubcfi(/6/2!/4/1:99)")
        assertEquals("epubcfi(/6/2!/4/1:99)", store.load("item-1"))
    }

    @Test
    fun `save stamps localUpdatedAt with current time`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        val before = System.currentTimeMillis()
        store.save("item-1", "cfi")
        val after = System.currentTimeMillis()
        val ts = dao.store["item-1"]?.localUpdatedAt ?: 0L
        assert(ts in before..after) { "Expected timestamp in [$before..$after] but was $ts" }
    }
}
