package com.riffle.core.data

import com.riffle.core.database.TocCacheDao
import com.riffle.core.database.TocCacheEntity
import com.riffle.core.domain.TestClock
import com.riffle.core.models.TocEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TocRepositoryImplTest {

    private companion object {
        const val NOW_MS: Long = 1_760_000_000_000L
    }

    private class FakeTocCacheDao : TocCacheDao {
        val store = mutableMapOf<Pair<String, String>, TocCacheEntity>()
        override suspend fun get(sourceId: String, itemId: String): TocCacheEntity? =
            store[sourceId to itemId]
        override suspend fun upsert(entity: TocCacheEntity) {
            store[entity.sourceId to entity.itemId] = entity
        }
    }

    @Test
    fun `getCachedToc returns null when no entry in dao`() = runTest {
        val dao = FakeTocCacheDao()
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))
        assertNull(repo.getCachedToc("srv", "item"))
    }

    @Test
    fun `getCachedToc returns inode and parsed entries`() = runTest {
        val dao = FakeTocCacheDao()
        val json = """[{"title":"Chapter 1","href":"ch1.html","children":[]}]"""
        dao.store["srv" to "item"] = TocCacheEntity("srv", "item", "ino42", json, cachedAt = NOW_MS)
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))

        val result = repo.getCachedToc("srv", "item")
        assertNotNull(result)
        assertEquals("ino42", result!!.first)
        assertEquals(1, result.second.size)
        assertEquals("Chapter 1", result.second[0].title)
        assertEquals("ch1.html", result.second[0].href)
    }

    @Test
    fun `saveToc upserts with correct inode and serialized JSON`() = runTest {
        val dao = FakeTocCacheDao()
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))
        val entries = listOf(TocEntry("Ch 1", "c1.html"), TocEntry("Ch 2", "c2.html"))

        repo.saveToc("srv", "item", "ino99", entries)

        val entity = dao.store["srv" to "item"]
        assertNotNull(entity)
        assertEquals("srv", entity!!.sourceId)
        assertEquals("item", entity.itemId)
        assertEquals("ino99", entity.ebookFileIno)
        assertEquals(NOW_MS, entity.cachedAt)
        assert(entity.entriesJson.contains("Ch 1")) { "JSON should contain 'Ch 1'" }
        assert(entity.entriesJson.contains("Ch 2")) { "JSON should contain 'Ch 2'" }
    }

    @Test
    fun `saveToc then getCachedToc round-trips entries`() = runTest {
        val dao = FakeTocCacheDao()
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))
        val entries = listOf(
            TocEntry("Part I", "part1.html", listOf(TocEntry("Chapter 1", "ch1.html"))),
            TocEntry("Part II", "part2.html"),
        )

        repo.saveToc("srv", "item", "ino-round", entries)
        val result = repo.getCachedToc("srv", "item")

        assertNotNull(result)
        assertEquals("ino-round", result!!.first)
        assertEquals(2, result.second.size)
        assertEquals("Part I", result.second[0].title)
        assertEquals(1, result.second[0].children.size)
        assertEquals("Chapter 1", result.second[0].children[0].title)
        assertEquals("Part II", result.second[1].title)
    }

    /**
     * Regression: TOC cache used to live forever (only invalidated by ebookFileIno change),
     * so a derivation-logic bug in [ExtractEpubTocUseCase] would stick on-device even after
     * a fix shipped. TTL rejects rows older than [com.riffle.core.common.DERIVED_CACHE_TTL_MS] so the
     * next open re-extracts.
     */
    @Test
    fun `getCachedToc returns null when entry is older than TTL`() = runTest {
        val dao = FakeTocCacheDao()
        val json = """[{"title":"Stale","href":"s.html","children":[]}]"""
        val cachedAt = NOW_MS - com.riffle.core.common.DERIVED_CACHE_TTL_MS - 1
        dao.store["srv" to "item"] = TocCacheEntity("srv", "item", "ino", json, cachedAt)
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))

        assertNull(repo.getCachedToc("srv", "item"))
    }

    /**
     * Existing rows migrated from the pre-TTL schema carry `cachedAt = 0` (DEFAULT 0 in
     * MIGRATION_55_56). They must be treated as maximally stale so any pre-existing bad
     * TOC heals on next open.
     */
    @Test
    fun `getCachedToc treats migrated rows with cachedAt=0 as stale`() = runTest {
        val dao = FakeTocCacheDao()
        val json = """[{"title":"Pre-migration","href":"p.html","children":[]}]"""
        dao.store["srv" to "item"] = TocCacheEntity("srv", "item", "ino", json, cachedAt = 0L)
        val repo = TocRepositoryImpl(dao, TestClock(NOW_MS))

        assertNull(repo.getCachedToc("srv", "item"))
    }
}
