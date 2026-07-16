package com.riffle.core.data

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogAudioFingerprint
import com.riffle.core.catalog.CatalogAudioTrack
import com.riffle.core.catalog.CatalogAudiobookChapter
import com.riffle.core.catalog.CatalogAudiobookStream
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookChapterCacheRepositoryImplTest {

    private companion object {
        const val NOW_MS: Long = 1_760_000_000_000L
    }

    private class FakeAudiobookChapterCacheDao : AudiobookChapterCacheDao {
        val store = mutableMapOf<Pair<String, String>, AudiobookChapterCacheEntity>()
        var upsertCalled = false
        override suspend fun get(sourceId: String, itemId: String): AudiobookChapterCacheEntity? =
            store[sourceId to itemId]
        override suspend fun upsert(entity: AudiobookChapterCacheEntity) {
            upsertCalled = true
            store[entity.sourceId to entity.itemId] = entity
        }
    }

    private class FakeCatalog(val chapters: List<CatalogAudiobookChapter>?, val fail: Boolean = false) : Catalog, AudiobookMediaCapability {
        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
        override suspend fun getTracks(itemId: String): List<CatalogAudioTrack> = emptyList()
        override suspend fun getFingerprint(itemId: String): CatalogAudioFingerprint? = CatalogAudioFingerprint(itemId, 0L, 0.0, emptyList())
        override fun buildStreamUrl(itemId: String, trackIno: String) = ""
        override suspend fun openAudiobook(itemId: String, deviceLabel: String): CatalogAudiobookStream? = null
        override suspend fun getAudiobookChapters(itemId: String): List<CatalogAudiobookChapter> {
            if (fail) throw RuntimeException("boom")
            return chapters!!
        }
    }

    private class FakeRegistry(private val catalog: Catalog?) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    @Test
    fun `getCachedChapters returns null when no cache`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(null), TestClock(NOW_MS))

        assertNull(repo.getCachedChapters("srv", "item"))
    }

    @Test
    fun `getCachedChapters returns deserialized chapters`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Intro"}]"""
        dao.store["srv" to "item"] = AudiobookChapterCacheEntity("srv", "item", json, cachedAt = NOW_MS)
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(null), TestClock(NOW_MS))

        val result = repo.getCachedChapters("srv", "item")
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("Intro", result[0].title)
        assertEquals(300.0, result[0].endSec, 0.001)
    }

    @Test
    fun `fetchAndCacheChapters calls catalog, maps chapters, and upserts`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val catalog = FakeCatalog(listOf(CatalogAudiobookChapter(0, 0.0, 600.0, "Ch 1")))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(catalog), TestClock(NOW_MS))

        val result = repo.fetchAndCacheChapters("srv", "item")

        assertEquals(1, result.size)
        assertEquals("Ch 1", result[0].title)
        assertEquals(0, result[0].index)
        assertEquals(0.0, result[0].startSec, 0.001)
        assertEquals(600.0, result[0].endSec, 0.001)
        assert(dao.upsertCalled) { "dao.upsert should have been called" }
        val entity = dao.store["srv" to "item"]
        assertNotNull(entity)
        assertEquals("srv", entity!!.sourceId)
        assertEquals("item", entity.itemId)
    }

    @Test
    fun `fetchAndCacheChapters returns empty list on network error`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(FakeCatalog(chapters = null, fail = true)), TestClock(NOW_MS))

        val result = repo.fetchAndCacheChapters("srv", "item")

        assertEquals(emptyList<AudiobookChapter>(), result)
        assert(!dao.upsertCalled) { "dao.upsert should NOT have been called on error" }
    }

    /**
     * Regression: chapter cache used to live forever, so a buggy `getAudiobookChapters`
     * result (e.g. Gramofonche chapters at half real length before the 128 kbps fallback
     * fix) stuck on-device with no self-heal. TTL rejects rows older than
     * [AudiobookChapterCacheRepositoryImpl.CACHE_TTL_MS] so the next open re-fetches.
     */
    @Test
    fun `getCachedChapters returns null when entry is older than TTL`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Stale"}]"""
        val cachedAt = NOW_MS - AudiobookChapterCacheRepositoryImpl.CACHE_TTL_MS - 1
        dao.store["srv" to "item"] = AudiobookChapterCacheEntity("srv", "item", json, cachedAt)
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(null), TestClock(NOW_MS))

        assertNull(repo.getCachedChapters("srv", "item"))
    }

    @Test
    fun `getCachedChapters returns cached entry right at the TTL boundary`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Fresh"}]"""
        val cachedAt = NOW_MS - AudiobookChapterCacheRepositoryImpl.CACHE_TTL_MS + 1
        dao.store["srv" to "item"] = AudiobookChapterCacheEntity("srv", "item", json, cachedAt)
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(null), TestClock(NOW_MS))

        val result = repo.getCachedChapters("srv", "item")
        assertNotNull(result)
        assertEquals("Fresh", result!![0].title)
    }

    /**
     * Existing rows migrated from the pre-TTL schema carry `cachedAt = 0` (DEFAULT 0 in
     * MIGRATION_54_55). They must be treated as maximally stale so any pre-existing bad
     * cache heals on next open — the whole reason we're shipping the TTL alongside the
     * Gramofonche chapter-duration fix.
     */
    @Test
    fun `getCachedChapters treats migrated rows with cachedAt=0 as stale`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Pre-migration"}]"""
        dao.store["srv" to "item"] = AudiobookChapterCacheEntity("srv", "item", json, cachedAt = 0L)
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(null), TestClock(NOW_MS))

        assertNull(repo.getCachedChapters("srv", "item"))
    }

    @Test
    fun `fetchAndCacheChapters stamps cachedAt with the current clock`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val catalog = FakeCatalog(listOf(CatalogAudiobookChapter(0, 0.0, 60.0, "One")))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(catalog), TestClock(NOW_MS))

        repo.fetchAndCacheChapters("srv", "item")

        assertEquals(NOW_MS, dao.store["srv" to "item"]!!.cachedAt)
    }

    @Test
    fun `fetchAndCacheChapters round-trips through getCachedChapters`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val catalog = FakeCatalog(listOf(
            CatalogAudiobookChapter(0, 0.0, 300.0, "Prologue"),
            CatalogAudiobookChapter(1, 300.0, 900.0, "Chapter 1"),
        ))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, FakeRegistry(catalog), TestClock(NOW_MS))

        repo.fetchAndCacheChapters("srv", "item")
        val cached = repo.getCachedChapters("srv", "item")

        assertNotNull(cached)
        assertEquals(2, cached!!.size)
        assertEquals("Prologue", cached[0].title)
        assertEquals(0, cached[0].index)
        assertEquals("Chapter 1", cached[1].title)
        assertEquals(1, cached[1].index)
        assertEquals(300.0, cached[1].startSec, 0.001)
    }
}
