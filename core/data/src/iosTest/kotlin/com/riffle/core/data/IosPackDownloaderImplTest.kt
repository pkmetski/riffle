package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.database.countDictSqliteEntries
import com.riffle.core.database.deleteRiffleDatabase
import com.riffle.core.database.openRiffleDatabase
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: IosPackDownloaderImpl was a no-op that always returned false, so tapping
 * "Download" in the dictionary packs screen never installed a pack on iOS.
 *
 * These tests drive the real iOS code path: Ktor MockEngine serves a JSONL payload, the
 * downloader converts it to SQLite, and the DAO reflects INSTALLED state.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPackDownloaderImplTest {

    private val roots = mutableListOf<String>()
    private lateinit var databaseName: String

    @BeforeTest
    fun setup() {
        databaseName = "riffle-pack-dl-test-${NSUUID().UUIDString}.db"
    }

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        runCatching { deleteRiffleDatabase(databaseName) }
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "pack_dl_test_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            NSFileManager.defaultManager.createDirectoryAtPath(
                base,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    private fun fixedClock(epochMs: Long = 1_700_000_000_000L): Clock = object : Clock {
        override fun nowMs(): Long = epochMs
        override fun nowNs(): Long = epochMs * 1_000_000L
    }

    private fun sampleJsonl(): String =
        "{\"word\":\"apple\",\"pos\":\"noun\",\"senses\":[{\"glosses\":[\"a round fruit\"]}]}\n" +
            "{\"word\":\"run\",\"pos\":\"verb\",\"senses\":[{\"glosses\":[\"to move quickly\"]}]}\n" +
            "{\"word\":\"run\",\"pos\":\"noun\",\"senses\":[{\"glosses\":[\"an act of running\"]}]}\n"

    private fun sampleEntry(languageTag: String = "en") = LanguageCatalogEntry(
        languageTag = languageTag,
        displayName = "English",
        jsonlUrl = "https://example.com/$languageTag.jsonl",
        approximateSizeBytes = 200L,
        attributionHtml = "",
        licenseUrl = "",
    )

    @Test
    fun downloadInstallsPack_andDaoReflectsInstalledState() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val dao = db.dictionaryPackDao()
            val body = sampleJsonl()
            val bodyBytes = body.encodeToByteArray()
            val mockEngine = MockEngine { _ ->
                respond(
                    content = bodyBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Length", bodyBytes.size.toString()),
                )
            }
            val downloader = IosPackDownloaderImpl(
                fileStore = TempFileStore(),
                httpClient = HttpClient(mockEngine),
                dictionaryPackDao = dao,
                clock = fixedClock(),
            )

            val result = downloader.download(sampleEntry()) { _, _ -> }

            assertTrue(result, "download should return true on success")
            val entity = dao.observeForLanguage("en").first()
            assertEquals(DictionaryPackState.INSTALLED.name, entity?.state)
            assertEquals("en", entity?.languageTag)
        } finally {
            db.close()
        }
    }

    @Test
    fun downloadedSqliteContainsEntries() = runTest {
        val db = openRiffleDatabase(databaseName)
        val fileStore = TempFileStore()
        try {
            val dao = db.dictionaryPackDao()
            val bodyBytes = sampleJsonl().encodeToByteArray()
            val mockEngine = MockEngine { _ ->
                respond(
                    content = bodyBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Length", bodyBytes.size.toString()),
                )
            }
            val downloader = IosPackDownloaderImpl(
                fileStore = fileStore,
                httpClient = HttpClient(mockEngine),
                dictionaryPackDao = dao,
                clock = fixedClock(),
            )

            val result = downloader.download(sampleEntry("test-lang")) { _, _ -> }
            assertTrue(result)

            val dbPath = fileStore.resolve("dicts", "test-lang.db")
            assertTrue(
                NSFileManager.defaultManager.fileExistsAtPath(dbPath),
                "SQLite file should exist at $dbPath",
            )

            val count = countDictSqliteEntries(dbPath)
            assertTrue(count >= 3L, "Expected ≥3 entries (apple noun, run verb, run noun) but got $count")
        } finally {
            db.close()
        }
    }

    @Test
    fun downloadReturnsFalse_onHttpError() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val dao = db.dictionaryPackDao()
            val mockEngine = MockEngine { _ ->
                respond(content = ByteArray(0), status = HttpStatusCode.NotFound)
            }
            val downloader = IosPackDownloaderImpl(
                fileStore = TempFileStore(),
                httpClient = HttpClient(mockEngine),
                dictionaryPackDao = dao,
                clock = fixedClock(),
            )
            val result = downloader.download(sampleEntry("fr")) { _, _ -> }
            assertFalse(result, "download should return false on HTTP error")
            val entity = dao.observeForLanguage("fr").first()
            assertEquals(DictionaryPackState.FAILED.name, entity?.state)
        } finally {
            db.close()
        }
    }
}
