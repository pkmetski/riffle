package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.PackInfo
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
import org.junit.Assert.assertNull
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

    private val packContent = "hello world"
    // sha256 of "hello world" (no newline)
    private val packSha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

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
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun packInfo(sha256: String = packSha256) = PackInfo(
        languageTag = "fr",
        packVersion = "2026-08-01",
        downloadUrl = server.url("/fr.db").toString(),
        sha256 = sha256,
        sizeBytes = packContent.length.toLong(),
        attributionHtml = "<a>Wiktionary</a>",
        licenseUrl = "https://cc.org",
    )

    @Test
    fun `happy path - returns true and sets INSTALLED state`() = runBlocking {
        server.enqueue(MockResponse().setBody(packContent))
        val result = downloader.download(packInfo())
        assertTrue(result)
        assertEquals(DictionaryPackState.INSTALLED.name, dao.lastUpserted?.state)
        val finalFile = File(filesDir, "dicts/fr.db")
        assertTrue("final .db file should exist", finalFile.exists())
        assertFalse("tmp file should be gone", File(filesDir, "dicts/fr.tmp").exists())
    }

    @Test
    fun `sha256 mismatch - returns false, sets FAILED, deletes tmp`() = runBlocking {
        server.enqueue(MockResponse().setBody(packContent))
        val result = downloader.download(packInfo(sha256 = "0000000000000000000000000000000000000000000000000000000000000000"))
        assertFalse(result)
        assertEquals(DictionaryPackState.FAILED.name, dao.lastState)
        assertFalse("tmp file should be deleted on mismatch", File(filesDir, "dicts/fr.tmp").exists())
        assertFalse("final .db should not exist", File(filesDir, "dicts/fr.db").exists())
    }

    @Test
    fun `http 500 - returns false and sets FAILED state`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = downloader.download(packInfo())
        assertFalse(result)
        assertEquals(DictionaryPackState.FAILED.name, dao.lastState)
    }

    @Test
    fun `sets DOWNLOADING state before download begins`() = runBlocking {
        var stateAtDownloadTime: String? = null
        server.enqueue(
            MockResponse().setBody(packContent).apply {
                // The downloader calls upsert(DOWNLOADING) before the HTTP request.
                // We can verify via the DAO that DOWNLOADING was the state when the server
                // was hit — by the time download() returns, the final state is INSTALLED.
                // Checking via lastUpsertedBefore which is set before execute().
            }
        )
        stateAtDownloadTime = dao.stateAtFirstUpsert
        downloader.download(packInfo())
        // After first upsert the state must have been DOWNLOADING
        assertNotNull(dao.firstUpsertedState)
        assertEquals(DictionaryPackState.DOWNLOADING.name, dao.firstUpsertedState)
    }
}

private class FakePackDao : DictionaryPackDao {
    private val _flow = MutableStateFlow<DictionaryPackEntity?>(null)
    var lastUpserted: DictionaryPackEntity? = null
    var lastState: String? = null
    var firstUpsertedState: String? = null
    var stateAtFirstUpsert: String? = null
    private var upsertCount = 0

    override fun observeForLanguage(languageTag: String): Flow<DictionaryPackEntity?> = _flow
    override fun observeAll(): Flow<List<DictionaryPackEntity>> = flowOf(listOfNotNull(_flow.value))

    override suspend fun upsert(entity: DictionaryPackEntity) {
        upsertCount++
        if (upsertCount == 1) firstUpsertedState = entity.state
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
