package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackDownloaderTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var dao: FakePackDao
    private lateinit var filesDir: File
    private lateinit var downloader: PackDownloader

    private val fakeConverter = FakeJsonlConverter()

    @Before
    fun setUp() {
        server.start()
        dao = FakePackDao()
        filesDir = tmpDir.newFolder("files")
        downloader = PackDownloader(
            filesDir = filesDir,
            httpClient = createTestHttpClient(),
            dictionaryPackDao = dao,
            clock = object : Clock {
                override fun nowMs() = 1000L
                override fun nowNs() = 1_000_000L
            },
            converter = fakeConverter,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun testEntry() = LanguageCatalogEntry(
        languageTag = "fr",
        displayName = "French",
        jsonlUrl = server.url("/fr.json").toString(),
        approximateSizeBytes = 150_000_000L,
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
    )

    @Test
    fun `happy path - returns true and final db exists`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val result = downloader.download(testEntry())
        assertTrue(result)
        assertEquals(DictionaryPackState.INSTALLED.name, dao.lastUpserted?.state)
        val finalFile = File(filesDir, "dicts/fr.db")
        assertTrue("final .db file should exist", finalFile.exists())
        assertFalse("tmp json file should be gone", File(filesDir, "dicts/fr.tmp.json").exists())
        assertFalse("tmp db file should be gone", File(filesDir, "dicts/fr.tmp.db").exists())
    }

    @Test
    fun `http 500 - returns false and sets FAILED state`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = downloader.download(testEntry())
        assertFalse(result)
        assertEquals(DictionaryPackState.FAILED.name, dao.lastState)
    }

    @Test
    fun `converter exception - returns false, sets FAILED, cleans up tmp files`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        fakeConverter.throwOnConvert = true
        val result = downloader.download(testEntry())
        assertFalse(result)
        assertEquals(DictionaryPackState.FAILED.name, dao.lastState)
        assertFalse("tmp json should be cleaned up", File(filesDir, "dicts/fr.tmp.json").exists())
        assertFalse("tmp db should be cleaned up", File(filesDir, "dicts/fr.tmp.db").exists())
    }

    @Test
    fun `DOWNLOADING state is upserted before http call`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        downloader.download(testEntry())
        assertNotNull(dao.firstUpsertedState)
        assertEquals(DictionaryPackState.DOWNLOADING.name, dao.firstUpsertedState)
    }

    @Test
    fun `upsert failure after rename - final db is deleted to avoid storage leak`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        dao.throwOnSecondUpsert = true
        val result = downloader.download(testEntry())
        assertFalse(result)
        assertFalse("orphaned final db should be cleaned up", File(filesDir, "dicts/fr.db").exists())
    }
}

private class FakeJsonlConverter : JsonlToSqliteConverter {
    var throwOnConvert = false
    override fun convert(jsonlFile: File, dbFile: File, onProgress: (Long, Long) -> Unit) {
        if (throwOnConvert) throw RuntimeException("converter error")
        dbFile.createNewFile()
    }
}

private class FakePackDao : DictionaryPackDao {
    private val _flow = MutableStateFlow<DictionaryPackEntity?>(null)
    var lastUpserted: DictionaryPackEntity? = null
    var lastState: String? = null
    var firstUpsertedState: String? = null
    var throwOnSecondUpsert = false
    private var upsertCount = 0

    override fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?> = _flow
    override fun observeAll(): Flow<List<DictionaryPackEntity>> = flowOf(listOfNotNull(_flow.value))

    override suspend fun upsert(entity: DictionaryPackEntity) {
        upsertCount++
        if (upsertCount == 1) firstUpsertedState = entity.state
        if (upsertCount == 2 && throwOnSecondUpsert) throw RuntimeException("DAO error on second upsert")
        lastUpserted = entity
        _flow.value = entity
    }

    override suspend fun updateState(languageTag: String, state: String) {
        lastState = state
        _flow.value = _flow.value?.copy(state = state)
    }

    override suspend fun delete(languageTag: String) {
        _flow.value = null
    }
}
