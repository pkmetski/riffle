package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionStoreTest {

    private class FakeReadingPositionDao(
        private val initial: MutableMap<String, String> = mutableMapOf()
    ) : ReadingPositionDao {
        val store: Map<String, String> get() = initial
        override suspend fun upsert(entity: ReadingPositionEntity) {
            initial[entity.itemId] = entity.cfi
        }
        override suspend fun getByItemId(itemId: String): ReadingPositionEntity? =
            initial[itemId]?.let { ReadingPositionEntity(itemId, it) }
    }

    @Test
    fun `save persists the CFI for the given item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao)
        store.save("item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")
        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", dao.store["item-1"])
    }

    @Test
    fun `load returns the saved CFI`() = runTest {
        val dao = FakeReadingPositionDao(mutableMapOf("item-1" to "epubcfi(/6/2!/4/1:42)"))
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
}
