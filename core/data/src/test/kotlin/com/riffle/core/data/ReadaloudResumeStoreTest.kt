package com.riffle.core.data

import com.riffle.core.database.ReadaloudResumePositionDao
import com.riffle.core.database.ReadaloudResumePositionEntity
import com.riffle.core.domain.ReadaloudResumePosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadaloudResumeStoreTest {

    private class FakeReadaloudResumePositionDao : ReadaloudResumePositionDao {
        private val entities: MutableMap<Pair<String, String>, ReadaloudResumePositionEntity> = mutableMapOf()
        val store: Map<Pair<String, String>, ReadaloudResumePositionEntity> get() = entities
        fun seed(entity: ReadaloudResumePositionEntity) { entities[entity.serverId to entity.itemId] = entity }
        override suspend fun upsert(entity: ReadaloudResumePositionEntity) {
            entities[entity.serverId to entity.itemId] = entity
        }
        override suspend fun getByItemId(serverId: String, itemId: String): ReadaloudResumePositionEntity? =
            entities[serverId to itemId]
    }

    @Test
    fun `save persists the resume position for the given item`() = runTest {
        val dao = FakeReadaloudResumePositionDao()
        val store = ReadaloudResumeStoreImpl(dao)
        store.save("server-A", "item-1", ReadaloudResumePosition("ch1.xhtml", 0.25, "ch1.xhtml#s5"))
        val saved = dao.store["server-A" to "item-1"]
        assertEquals("ch1.xhtml", saved?.href)
        assertEquals(0.25, saved?.progression!!, 1e-9)
        assertEquals("ch1.xhtml#s5", saved.fragmentRef)
    }

    @Test
    fun `load returns the saved resume position`() = runTest {
        val dao = FakeReadaloudResumePositionDao().also {
            it.seed(ReadaloudResumePositionEntity("server-A", "item-1", "ch2.xhtml", 0.5, "ch2.xhtml#s9"))
        }
        val store = ReadaloudResumeStoreImpl(dao)
        assertEquals(ReadaloudResumePosition("ch2.xhtml", 0.5, "ch2.xhtml#s9"), store.load("server-A", "item-1"))
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = ReadaloudResumeStoreImpl(FakeReadaloudResumePositionDao())
        assertNull(store.load("server-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same server-item`() = runTest {
        val dao = FakeReadaloudResumePositionDao()
        val store = ReadaloudResumeStoreImpl(dao)
        store.save("server-A", "item-1", ReadaloudResumePosition("ch1.xhtml", 0.1, "ch1.xhtml#s1"))
        store.save("server-A", "item-1", ReadaloudResumePosition("ch3.xhtml", 0.9, "ch3.xhtml#s9"))
        assertEquals(ReadaloudResumePosition("ch3.xhtml", 0.9, "ch3.xhtml#s9"), store.load("server-A", "item-1"))
    }

    @Test
    fun `null progression and fragmentRef round-trip`() = runTest {
        val dao = FakeReadaloudResumePositionDao()
        val store = ReadaloudResumeStoreImpl(dao)
        store.save("server-A", "item-1", ReadaloudResumePosition("ch1.xhtml", null, null))
        assertEquals(ReadaloudResumePosition("ch1.xhtml", null, null), store.load("server-A", "item-1"))
    }

    @Test
    fun `save stamps localUpdatedAt with current time`() = runTest {
        val dao = FakeReadaloudResumePositionDao()
        val store = ReadaloudResumeStoreImpl(dao)
        val before = System.currentTimeMillis()
        store.save("server-A", "item-1", ReadaloudResumePosition("ch1.xhtml", 0.0, "ch1.xhtml#s1"))
        val after = System.currentTimeMillis()
        val ts = dao.store["server-A" to "item-1"]?.localUpdatedAt ?: 0L
        assert(ts in before..after) { "Expected timestamp in [$before..$after] but was $ts" }
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeReadaloudResumePositionDao()
        val store = ReadaloudResumeStoreImpl(dao)
        store.save("server-A", "item-1", ReadaloudResumePosition("a.xhtml", 0.1, "a.xhtml#s1"))
        store.save("server-B", "item-1", ReadaloudResumePosition("b.xhtml", 0.9, "b.xhtml#s9"))
        assertEquals("a.xhtml", store.load("server-A", "item-1")?.href)
        assertEquals("b.xhtml", store.load("server-B", "item-1")?.href)
    }
}
