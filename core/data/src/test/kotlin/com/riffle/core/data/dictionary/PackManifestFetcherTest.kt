package com.riffle.core.data.dictionary

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PackManifestFetcherTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher() = PackManifestFetcher(
        httpClient = createTestHttpClient(),
        manifestUrl = server.url("/dict-manifest.json").toString(),
    )

    @Test
    fun `parses valid manifest`() {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"version":1,"packs":[{"languageTag":"fr","packVersion":"2026-08-01",
                       |"downloadUrl":"https://example.com/fr.db","sha256":"abc123","sizeBytes":1234567,
                       |"attributionHtml":"Wiktionary","licenseUrl":"https://cc.org"}]}""".trimMargin()
                )
        )
        val result = runBlocking { fetcher().fetch() }
        assertEquals(1, result.version)
        assertEquals(1, result.packs.size)
        val pack = result.packs[0]
        assertEquals("fr", pack.languageTag)
        assertEquals("2026-08-01", pack.packVersion)
        assertEquals(1234567L, pack.sizeBytes)
    }

    @Test
    fun `throws on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(Exception::class.java) {
            runBlocking { fetcher().fetch() }
        }
    }

    @Test
    fun `throws on malformed JSON`() {
        server.enqueue(MockResponse().setBody("not json"))
        assertThrows(Exception::class.java) {
            runBlocking { fetcher().fetch() }
        }
    }
}
