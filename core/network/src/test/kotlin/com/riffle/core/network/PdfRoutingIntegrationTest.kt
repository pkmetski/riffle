package com.riffle.core.network

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.models.EbookFormat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PDF vs EPUB routing wired to real Library Item metadata from stub server.
 *
 * Verifies the full path: JSON response → network model → ebookFormat sealed type.
 * Routing (readerRouteFor) lives in :app and can't be imported here, so we assert
 * the EbookFormat on the parsed items — the routing unit test in :app covers the
 * format → route mapping exhaustively.
 */
class PdfRoutingIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pdf and epub items from server response route to correct navigator`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[
                        {"id":"epub-1","libraryId":"lib-1","media":{"metadata":{"title":"My EPUB","authorName":"Author"},"ebookFormat":"epub","ebookFile":{"ino":"ino-1"}}},
                        {"id":"pdf-1","libraryId":"lib-1","media":{"metadata":{"title":"My PDF","authorName":"Author"},"ebookFormat":"pdf","ebookFile":{"ino":"ino-2"}}},
                        {"id":"audio-1","libraryId":"lib-1","media":{"metadata":{"title":"My Audiobook","authorName":"Author"}}}
                    ]}"""
                )
                .addHeader("Content-Type", "application/json")
        )
        val result = client.getLibraryItems(
            server.url("/").toString().trimEnd('/'), "lib-1", "token", false
        ) as NetworkResult.Success

        val byId = result.value.associateBy { it.id }

        assertEquals(EbookFormat.Epub, byId["epub-1"]!!.ebookFormat)
        assertEquals(EbookFormat.Pdf, byId["pdf-1"]!!.ebookFormat)
        assertEquals(EbookFormat.Unsupported, byId["audio-1"]!!.ebookFormat)
    }
}
