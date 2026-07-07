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
            sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(cfi = position, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmPushedIfUnchanged(
            sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
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
    }

    @Test
    fun `save persists the CFI for the given item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.save("source-A", "item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")
        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", dao.store["source-A" to "item-1"]?.cfi)
    }

    @Test
    fun `load returns the saved CFI`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertEquals("epubcfi(/6/2!/4/1:42)", store.load("source-A", "item-1"))
    }

    @Test
    fun `load returns null for an item with no saved position`() = runTest {
        val store = ReadingPositionStoreImpl(FakeReadingPositionDao(), com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertNull(store.load("source-A", "item-new"))
    }

    @Test
    fun `save overwrites the previous position for the same source-item`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:99)")
        assertEquals("epubcfi(/6/2!/4/1:99)", store.load("source-A", "item-1"))
    }

    @Test
    fun `save stamps localUpdatedAt from the injected clock`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(initialMs = 1_700_000_000_000L))
        store.save("source-A", "item-1", "cfi")
        assertEquals(1_700_000_000_000L, dao.store["source-A" to "item-1"]?.localUpdatedAt)
    }

    @Test
    fun `positions for the same itemId on different servers are isolated`() = runTest {
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))

        store.save("source-A", "item-1", "epubcfi(/6/2!/4/1:10)")
        store.save("source-B", "item-1", "epubcfi(/6/8!/4/1:99)")

        assertEquals("epubcfi(/6/2!/4/1:10)", store.load("source-A", "item-1"))
        assertEquals("epubcfi(/6/8!/4/1:99)", store.load("source-B", "item-1"))
        assertNotEquals(store.load("source-A", "item-1"), store.load("source-B", "item-1"))
    }

    @Test
    fun `save never regresses localUpdatedAt below an adopted future source stamp`() = runTest {
        // Regression: when ABS's clock is ahead of the device, the sync cycle adopts a future source
        // stamp as localUpdatedAt. A subsequent save() using raw now() would silently lower
        // localUpdatedAt back under the source's last-known stamp, making every next cycle conclude
        // source-wins and yank the reader to the older source position — the "periodic sync
        // overwrites my position" bug. save() must always advance localUpdatedAt strictly past
        // whatever's already stored.
        val dao = FakeReadingPositionDao()
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        val futureServerStamp = System.currentTimeMillis() + 120_000L // 2 minutes ahead
        dao.seed(ReadingPositionEntity("source-A", "item-1", "old", futureServerStamp, futureServerStamp))

        store.save("source-A", "item-1", "fresh")

        val after = dao.store["source-A" to "item-1"]?.localUpdatedAt ?: 0L
        assert(after > futureServerStamp) {
            "save() must advance localUpdatedAt past the adopted source stamp; was $after, source stamp $futureServerStamp"
        }
        assertEquals("fresh", store.load("source-A", "item-1"))
    }

    @Test
    fun `load on a different source returns null even when itemId is saved elsewhere`() = runTest {
        val dao = FakeReadingPositionDao().also {
            it.seed(ReadingPositionEntity("source-A", "item-1", "epubcfi(/6/2!/4/1:42)"))
        }
        val store = ReadingPositionStoreImpl(dao, com.riffle.core.domain.TestClock(System.currentTimeMillis()))
        assertNull(store.load("source-B", "item-1"))
    }
}
