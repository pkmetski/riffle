package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.database.AudiobookChapterCacheDao
import com.riffle.core.database.AudiobookChapterCacheEntity
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.model.AbsItemChapterDto
import com.riffle.core.network.model.AbsItemDetailMediaDto
import com.riffle.core.network.model.AbsItemDetailResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookChapterCacheRepositoryImplTest {

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

    private fun fakeApiReturning(result: NetworkResult<com.riffle.core.network.model.AbsItemDetailResponse>) = object : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibrary>> =
            throw NotImplementedError()
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibraryItem>> =
            throw NotImplementedError()
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkSeries>> =
            throw NotImplementedError()
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkCollection>> =
            throw NotImplementedError()
        override suspend fun getItemDetail(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<com.riffle.core.network.model.AbsItemDetailResponse> =
            result
    }

    @Test
    fun `getCachedChapters returns null when no cache`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val api = fakeApiReturning(NetworkResult.Offline(RuntimeException("not called")))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

        assertNull(repo.getCachedChapters("srv", "item"))
    }

    @Test
    fun `getCachedChapters returns deserialized chapters`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val json = """[{"index":0,"startSec":0.0,"endSec":300.0,"title":"Intro"}]"""
        dao.store["srv" to "item"] = AudiobookChapterCacheEntity("srv", "item", json)
        val api = fakeApiReturning(NetworkResult.Offline(RuntimeException("not called")))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

        val result = repo.getCachedChapters("srv", "item")
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("Intro", result[0].title)
        assertEquals(300.0, result[0].endSec, 0.001)
    }

    @Test
    fun `fetchAndCacheChapters calls api, maps chapters, and upserts`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val api = fakeApiReturning(
            NetworkResult.Success(
                AbsItemDetailResponse(
                    id = "item",
                    media = AbsItemDetailMediaDto(
                        chapters = listOf(
                            AbsItemChapterDto(id = 0, startSec = 0.0, endSec = 600.0, title = "Ch 1")
                        )
                    ),
                )
            )
        )
        val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

        val result = repo.fetchAndCacheChapters("srv", "item", "http://base", "tok", false)

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
        val api = fakeApiReturning(NetworkResult.Offline(RuntimeException("network fail")))
        val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

        val result = repo.fetchAndCacheChapters("srv", "item", "http://base", "tok", false)

        assertEquals(emptyList<AudiobookChapter>(), result)
        assert(!dao.upsertCalled) { "dao.upsert should NOT have been called on error" }
    }

    @Test
    fun `fetchAndCacheChapters round-trips through getCachedChapters`() = runTest {
        val dao = FakeAudiobookChapterCacheDao()
        val api = fakeApiReturning(
            NetworkResult.Success(
                AbsItemDetailResponse(
                    id = "item",
                    media = AbsItemDetailMediaDto(
                        chapters = listOf(
                            AbsItemChapterDto(id = 0, startSec = 0.0, endSec = 300.0, title = "Prologue"),
                            AbsItemChapterDto(id = 1, startSec = 300.0, endSec = 900.0, title = "Chapter 1"),
                        )
                    ),
                )
            )
        )
        val repo = AudiobookChapterCacheRepositoryImpl(dao, api)

        repo.fetchAndCacheChapters("srv", "item", "http://base", "tok", false)
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
