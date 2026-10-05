package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavPlaylistSyncerTest {

    private val config = AnnotationSyncConfig(
        baseUrl = "https://dav.example.com/riffle/",
        username = "alice",
        password = "secret",
    )

    private fun syncer(engine: MockEngine) = WebDavPlaylistSyncer(
        config = config,
        httpClient = HttpClient(engine),
    )

    // ---- pull ----

    @Test
    fun pull_returns_null_on_404() = runTest {
        val engine = MockEngine { respond(ByteReadChannel.Empty, HttpStatusCode.NotFound) }
        assertNull(syncer(engine).pull("chitanka", "toread-books"))
    }

    @Test
    fun pull_parses_json_and_LastModified() = runTest {
        val body = """{"id":"toread-books","name":"To Read","libraryId":"books","itemIds":["item1"],"lastUpdate":1000}"""
        val engine = MockEngine {
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT"),
            )
        }
        val result = syncer(engine).pull("chitanka", "toread-books")!!
        assertEquals("toread-books", result.id)
        assertEquals(listOf("item1"), result.itemIds)
    }

    // ---- push ----

    @Test
    fun push_sends_PUT_with_correct_url_and_returns_serverTimestamp() = runTest {
        var capturedUrl = ""
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.NoContent,
                headersOf("Last-Modified", "Fri, 02 Jan 2026 00:00:00 GMT"),
            )
        }
        val playlist = WebDavPlaylist(
            id = "toread-books",
            name = "To Read",
            libraryId = "books",
            itemIds = listOf("item1", "item2"),
            lastUpdate = 9000L,
        )
        val serverTs = syncer(engine).push("chitanka", playlist)
        assertTrue(capturedUrl.contains("chitanka__playlist_toread-books.json"), "url=$capturedUrl")
        assertTrue(capturedBody.contains("\"itemIds\""), "body=$capturedBody")
        assertTrue(serverTs > 0L, "serverTs=$serverTs")
    }

    @Test
    fun push_with_no_trailing_slash_url_produces_correct_path() = runTest {
        val configNoSlash = AnnotationSyncConfig(
            baseUrl = "https://dav.example.com/riffle",
            username = "alice",
            password = "secret",
        )
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                ByteReadChannel.Empty,
                HttpStatusCode.NoContent,
                headersOf("Last-Modified", "Fri, 02 Jan 2026 00:00:00 GMT"),
            )
        }
        val playlist = WebDavPlaylist(
            id = "toread-books",
            name = "To Read",
            libraryId = "books",
            itemIds = emptyList(),
            lastUpdate = 0L,
        )
        WebDavPlaylistSyncer(config = configNoSlash, httpClient = HttpClient(engine))
            .push("chitanka", playlist)
        assertTrue(
            capturedUrl.contains("/riffle/chitanka__playlist_toread-books.json"),
            "url=$capturedUrl",
        )
    }

    // ---- list ----

    @Test
    fun list_returns_empty_when_404() = runTest {
        val engine = MockEngine { respond(ByteReadChannel.Empty, HttpStatusCode.NotFound) }
        assertEquals(emptyList(), syncer(engine).list("chitanka"))
    }

    @Test
    fun list_filters_and_parses_playlist_files() = runTest {
        val propfindXml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/riffle/chitanka__playlist_toread-books.json</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
              <D:response><D:href>/riffle/chitanka__progress_book.123__ebook_progress.json</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
              <D:response><D:href>/riffle/</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>""".trimIndent()
        val playlistBody = """{"id":"toread-books","name":"To Read","libraryId":"books","itemIds":[],"lastUpdate":0}"""
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            when {
                request.method == HttpMethod("PROPFIND") ->
                    respond(ByteReadChannel(propfindXml), HttpStatusCode.MultiStatus)
                request.url.encodedPath.contains("toread-books") ->
                    respond(ByteReadChannel(playlistBody), HttpStatusCode.OK)
                else -> respond(ByteReadChannel.Empty, HttpStatusCode.NotFound)
            }
        }
        val results = syncer(engine).list("chitanka")
        assertEquals(1, results.size, "expected 1 playlist, got ${results.size}")
        assertEquals("toread-books", results[0].id)
    }

    // ---- toReadPlaylistId ----

    @Test
    fun toReadPlaylistId_returns_deterministic_id() {
        assertEquals("toread-my-library", WebDavPlaylistSyncer.toReadPlaylistId("my-library"))
    }
}
