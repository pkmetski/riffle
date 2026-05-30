package com.riffle.app.harness

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Wraps MockWebServer to serve canned ABS API responses for instrumented tests.
 * All constants are stable identifiers that tests can reference without magic strings.
 *
 * Three items exist:
 *  - TEST_ITEM (item-test-1): belongs to TEST_SERIES and TEST_COLLECTION — used for grouped navigation tests.
 *  - TEST_STANDALONE_ITEM (item-test-2): not in any series or collection — appears in the ungrouped "Books"
 *    section of the library items screen and is used for the direct-open EPUB test.
 *  - TEST_PDF_ITEM (item-test-3): a PDF item used for the PDF reader harness test.
 */
class StubAbsServer {

    companion object {
        const val TEST_USER_ID = "test-user-id"
        const val TEST_TOKEN = "test-token"
        const val TEST_LIBRARY_ID = "lib-test-1"
        const val TEST_LIBRARY_NAME = "Test Library"
        const val TEST_ITEM_ID = "item-test-1"
        const val TEST_ITEM_TITLE = "Test EPUB"
        const val TEST_ITEM_AUTHOR = "Test Author"
        const val TEST_FILE_INO = "ino-test-1"
        const val TEST_SERIES_ID = "series-test-1"
        const val TEST_SERIES_NAME = "Test Series"
        const val TEST_COLLECTION_ID = "collection-test-1"
        const val TEST_COLLECTION_NAME = "Test Collection"
        const val TEST_PLAYLIST_ID = "playlist-test-1"
        const val TEST_PLAYLIST_NAME = "To Read"
        const val TEST_STANDALONE_ITEM_ID = "item-test-2"
        const val TEST_STANDALONE_ITEM_TITLE = "Test EPUB Standalone"
        const val TEST_STANDALONE_FILE_INO = "ino-test-2"
        const val TEST_PDF_ITEM_ID = "item-test-3"
        const val TEST_PDF_ITEM_TITLE = "Test PDF"
        const val TEST_PDF_FILE_INO = "ino-test-3"
        const val TEST_FOOTNOTE_ITEM_ID = "item-test-4"
        const val TEST_FOOTNOTE_ITEM_TITLE = "Test Footnotes EPUB"
        const val TEST_FOOTNOTE_FILE_INO = "ino-test-4"
    }

    private val _sessionSyncCount = java.util.concurrent.atomic.AtomicInteger(0)
    val sessionSyncCount: Int get() = _sessionSyncCount.get()

    @Volatile var lastProgressPath: String? = null
        private set
    @Volatile var lastProgressBody: String? = null
        private set

    private val server = MockWebServer()

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = StubDispatcher()
        server.start()
    }

    fun shutdown() = server.shutdown()

    private inner class StubDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/login" && request.method == "POST" -> loginResponse()
            request.path == "/api/libraries" -> librariesResponse()
            request.path == "/api/libraries/$TEST_LIBRARY_ID/items" -> libraryItemsResponse()
            request.path == "/api/libraries/$TEST_LIBRARY_ID/series?limit=500" -> seriesResponse()
            request.path == "/api/libraries/$TEST_LIBRARY_ID/collections?limit=500" -> collectionsResponse()
            request.path == "/api/libraries/$TEST_LIBRARY_ID/playlists?limit=500" -> playlistsResponse()
            request.path == "/api/playlists" && request.method == "POST" -> playlistCreateResponse(request)
            request.path?.matches(Regex("/api/playlists/[^/]+/item")) == true && request.method == "POST" -> playlistItemAddResponse()
            request.path?.matches(Regex("/api/playlists/[^/]+/item/[^/]+")) == true && request.method == "DELETE" -> playlistItemRemoveResponse()
            request.path == "/api/items/$TEST_ITEM_ID" -> itemResponse()
            request.path == "/api/items/$TEST_ITEM_ID/ebook/$TEST_FILE_INO" -> epubFileResponse()
            request.path == "/api/items/$TEST_STANDALONE_ITEM_ID" -> standaloneItemResponse()
            request.path == "/api/items/$TEST_STANDALONE_ITEM_ID/ebook/$TEST_STANDALONE_FILE_INO" -> epubFileResponse()
            request.path == "/api/items/$TEST_PDF_ITEM_ID" -> pdfItemResponse()
            request.path == "/api/items/$TEST_PDF_ITEM_ID/ebook/$TEST_PDF_FILE_INO" -> pdfFileResponse()
            request.path == "/api/items/$TEST_FOOTNOTE_ITEM_ID" -> footnoteItemResponse()
            request.path == "/api/items/$TEST_FOOTNOTE_ITEM_ID/ebook/$TEST_FOOTNOTE_FILE_INO" -> footnoteFileResponse()
            request.path == "/api/me" && request.method == "GET" -> meResponse()
            request.path?.matches(Regex("/api/me/progress/[^/]+")) == true && request.method == "GET" -> progressGetResponse()
            request.path?.matches(Regex("/api/me/progress/[^/]+")) == true && request.method == "PATCH" -> progressSyncResponse(request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun loginResponse() = json(
        200,
        """{"user":{"id":"$TEST_USER_ID","username":"testuser","token":"$TEST_TOKEN"}}"""
    )

    private fun librariesResponse() = json(
        200,
        """{"libraries":[{"id":"$TEST_LIBRARY_ID","name":"$TEST_LIBRARY_NAME","mediaType":"book","settings":{"audiobooksOnly":false}}]}"""
    )

    private fun libraryItemsResponse() = json(
        200,
        """{"results":[
          {"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null},
          {"id":"$TEST_STANDALONE_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_STANDALONE_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_STANDALONE_FILE_INO"}},"userMediaProgress":null},
          {"id":"$TEST_PDF_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_PDF_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"pdf","ebookFile":{"ino":"$TEST_PDF_FILE_INO"}},"userMediaProgress":null},
          {"id":"$TEST_FOOTNOTE_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_FOOTNOTE_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FOOTNOTE_FILE_INO"}},"userMediaProgress":null}
        ]}"""
    )

    private fun itemResponse() = json(
        200,
        """{"id":"$TEST_ITEM_ID","media":{"ebookFile":{"ino":"$TEST_FILE_INO"}}}"""
    )

    private fun standaloneItemResponse() = json(
        200,
        """{"id":"$TEST_STANDALONE_ITEM_ID","media":{"ebookFile":{"ino":"$TEST_STANDALONE_FILE_INO"}}}"""
    )

    private fun pdfItemResponse() = json(
        200,
        """{"id":"$TEST_PDF_ITEM_ID","media":{"ebookFile":{"ino":"$TEST_PDF_FILE_INO"}}}"""
    )

    private fun epubFileResponse(): MockResponse {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("test.epub").use { it.readBytes() }
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/epub+zip")
            .setBody(okio.Buffer().write(bytes))
    }

    private fun pdfFileResponse(): MockResponse {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("test.pdf").use { it.readBytes() }
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/pdf")
            .setBody(okio.Buffer().write(bytes))
    }

    private fun footnoteItemResponse() = json(
        200,
        """{"id":"$TEST_FOOTNOTE_ITEM_ID","media":{"ebookFile":{"ino":"$TEST_FOOTNOTE_FILE_INO"}}}"""
    )

    private fun footnoteFileResponse(): MockResponse {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("test-footnotes.epub").use { it.readBytes() }
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/epub+zip")
            .setBody(okio.Buffer().write(bytes))
    }

    private fun seriesResponse() = json(
        200,
        """{"results":[{"id":"$TEST_SERIES_ID","libraryId":"$TEST_LIBRARY_ID","name":"$TEST_SERIES_NAME","books":[{"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","seriesSequence":"1","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null}]}]}"""
    )

    private fun collectionsResponse() = json(
        200,
        """{"results":[{"id":"$TEST_COLLECTION_ID","libraryId":"$TEST_LIBRARY_ID","name":"$TEST_COLLECTION_NAME","books":[{"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null}]}]}"""
    )

    private fun playlistsResponse() = json(
        200,
        """{"results":[]}"""
    )

    private fun playlistCreateResponse(request: RecordedRequest): MockResponse {
        // Consume body to avoid leaving it on the wire; minimal valid playlist response.
        request.body.readUtf8()
        return json(
            200,
            """{"id":"playlist-new","libraryId":"$TEST_LIBRARY_ID","name":"$TEST_PLAYLIST_NAME","items":[]}"""
        )
    }

    private fun playlistItemAddResponse() = json(200, "{}")

    private fun playlistItemRemoveResponse() = json(200, "{}")

    private fun meResponse() = json(
        200,
        """{"mediaProgress":[]}"""
    )

    private fun progressGetResponse() = json(
        200,
        """{"ebookLocation":"","ebookProgress":0.0,"lastUpdate":-1}"""
    )

    private fun progressSyncResponse(request: RecordedRequest): MockResponse {
        lastProgressPath = request.path
        lastProgressBody = request.body.readUtf8()
        _sessionSyncCount.incrementAndGet()
        return json(200, "{}")
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
