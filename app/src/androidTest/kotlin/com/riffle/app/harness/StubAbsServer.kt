package com.riffle.app.harness

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Wraps MockWebServer to serve canned ABS API responses for instrumented tests.
 * All constants are stable identifiers that tests can reference without magic strings.
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
    }

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
            request.path == "/api/items/$TEST_ITEM_ID" -> itemResponse()
            request.path == "/api/items/$TEST_ITEM_ID/ebook/$TEST_FILE_INO" -> epubFileResponse()
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
        """{"results":[{"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR"},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null}]}"""
    )

    private fun itemResponse() = json(
        200,
        """{"id":"$TEST_ITEM_ID","media":{"ebookFile":{"ino":"$TEST_FILE_INO"}}}"""
    )

    private fun epubFileResponse(): MockResponse {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("test.epub").use { it.readBytes() }
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/epub+zip")
            .setBody(okio.Buffer().write(bytes))
    }

    private fun seriesResponse() = json(
        200,
        """{"results":[{"id":"$TEST_SERIES_ID","libraryId":"$TEST_LIBRARY_ID","name":"$TEST_SERIES_NAME","books":[{"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","seriesSequence":"1","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR"},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null}]}]}"""
    )

    private fun collectionsResponse() = json(
        200,
        """{"results":[{"id":"$TEST_COLLECTION_ID","libraryId":"$TEST_LIBRARY_ID","name":"$TEST_COLLECTION_NAME","books":[{"id":"$TEST_ITEM_ID","libraryId":"$TEST_LIBRARY_ID","media":{"metadata":{"title":"$TEST_ITEM_TITLE","authorName":"$TEST_ITEM_AUTHOR"},"ebookFormat":"epub","ebookFile":{"ino":"$TEST_FILE_INO"}},"userMediaProgress":null}]}]}"""
    )

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
