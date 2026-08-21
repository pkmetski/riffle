package com.riffle.core.sources.webdav

import com.riffle.core.common.Clock
import com.riffle.core.domain.DefaultDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebDavProgressRemoteTest {

    private val clock = object : Clock {
        override fun nowMs() = 1_724_000_000_000L
        override fun nowNs() = 0L
    }

    private val progressUrl = "https://webdav.example.com/riffle/chitanka__book-123__progress.json"
    private val authHeader = "Basic dXNlcjpwYXNz"

    private fun makeRemote(
        readingProgress: Float = 0.42f,
        finishedAt: Long? = null,
        engine: MockEngine,
    ) = WebDavProgressRemote(
        client = HttpClient(engine),
        authHeader = authHeader,
        progressFileUrl = progressUrl,
        readingProgress = { readingProgress },
        finishedAt = { finishedAt },
        dispatchers = DefaultDispatcherProvider,
        clock = clock,
    )

    private fun okEngine(
        body: String,
        vararg extraHeaders: Pair<String, List<String>>,
    ) = MockEngine {
        respond(
            ByteReadChannel(body.toByteArray()),
            HttpStatusCode.OK,
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json; charset=utf-8"),
                *extraHeaders,
            ),
        )
    }

    private fun statusEngine(status: HttpStatusCode, vararg extraHeaders: Pair<String, List<String>>) =
        MockEngine {
            respond(ByteReadChannel(ByteArray(0)), status, headers = headersOf(*extraHeaders))
        }

    // ── get() ──────────────────────────────────────────────────────────────

    // 404 previously returned null (Offline), preventing first-sync file creation.
    // Now returns RemoteProgress(lastUpdate=0) so a dirty local row fires LocalWins.
    // Removed-test: get returns null on 404
    @Test fun `get on 404 returns RemoteProgress with lastUpdate=0 to enable first-sync push`() = runTest {
        val r = makeRemote(engine = statusEngine(HttpStatusCode.NotFound))
        val result = r.get()
        assertNotNull(result)
        assertEquals(0L, result!!.lastUpdate)
        assertEquals("", result.position)
        assertEquals(0f, result.readingProgress, 0.001f)
        assertNull(result.finishedAt)
    }

    @Test fun `get returns null on non-2xx non-404`() = runTest {
        val r = makeRemote(engine = statusEngine(HttpStatusCode.InternalServerError))
        assertNull(r.get())
    }

    @Test fun `get returns null on IOException`() = runTest {
        val r = makeRemote(engine = MockEngine { throw IOException("network error") })
        assertNull(r.get())
    }

    @Test fun `get parses payload and uses Last-Modified header as lastUpdate`() = runTest {
        val body = """{"position":"{\"href\":\"/ch1.html\"}","readingProgress":0.42,"finishedAt":null,"lastUpdate":1724000000000}"""
        val r = makeRemote(
            engine = okEngine(body, HttpHeaders.LastModified to listOf("Mon, 18 Aug 2025 12:00:00 GMT")),
        )
        val result = r.get()
        assertNotNull(result)
        assertEquals("{\"href\":\"/ch1.html\"}", result!!.position)
        assertEquals(0.42f, result.readingProgress, 0.001f)
        assertNull(result.finishedAt)
        assertTrue("Last-Modified must be parsed to a positive epoch ms", result.lastUpdate > 0L)
    }

    @Test fun `get falls back to payload lastUpdate when Last-Modified header absent`() = runTest {
        val body = """{"position":"{}","readingProgress":0.1,"finishedAt":null,"lastUpdate":1724000000000}"""
        val r = makeRemote(engine = okEngine(body))
        val result = r.get()
        assertNotNull(result)
        assertEquals(1_724_000_000_000L, result!!.lastUpdate)
    }

    @Test fun `get propagates finishedAt from payload`() = runTest {
        val body = """{"position":"{}","readingProgress":1.0,"finishedAt":1724000000000,"lastUpdate":1724000000000}"""
        val r = makeRemote(engine = okEngine(body))
        val result = r.get()
        assertNotNull(result)
        assertEquals(1_724_000_000_000L, result!!.finishedAt)
    }

    // ── patch() ────────────────────────────────────────────────────────────

    @Test fun `patch PUTs JSON with correct position and readingProgress`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { req ->
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond(
                ByteReadChannel(ByteArray(0)),
                HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.LastModified to listOf("Mon, 18 Aug 2025 12:00:00 GMT")),
            )
        }
        val r = makeRemote(readingProgress = 0.42f, finishedAt = null, engine = engine)
        r.patch("{\"href\":\"/ch1.html\"}")

        assertEquals(1, engine.requestHistory.size)
        assertEquals("PUT", engine.requestHistory[0].method.value)
        assertTrue(engine.requestHistory[0].url.toString().endsWith("chitanka__book-123__progress.json"))
        val payload = WebDavProgressRemote.json
            .decodeFromString(WebDavProgressRemote.ProgressPayload.serializer(), capturedBody)
        assertEquals("{\"href\":\"/ch1.html\"}", payload.position)
        assertEquals(0.42f, payload.readingProgress, 0.001f)
        assertNull(payload.finishedAt)
    }

    @Test fun `patch writes finishedAt from injected lambda when book is finished`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { req ->
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.NoContent, headers = headersOf())
        }
        val r = makeRemote(finishedAt = 1_724_000_000_000L, engine = engine)
        r.patch("{}")

        val payload = WebDavProgressRemote.json
            .decodeFromString(WebDavProgressRemote.ProgressPayload.serializer(), capturedBody)
        assertEquals(1_724_000_000_000L, payload.finishedAt)
    }

    @Test fun `patch returns Last-Modified from response as server stamp`() = runTest {
        val engine = MockEngine {
            respond(
                ByteReadChannel(ByteArray(0)),
                HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.LastModified to listOf("Mon, 18 Aug 2025 12:00:00 GMT")),
            )
        }
        val stamp = makeRemote(engine = engine).patch("{}")
        assertNotNull(stamp)
        assertTrue(stamp!! > 0L)
    }

    @Test fun `patch falls back to clock nowMs when Last-Modified absent`() = runTest {
        val r = makeRemote(engine = statusEngine(HttpStatusCode.NoContent))
        assertEquals(1_724_000_000_000L, r.patch("{}"))
    }

    @Test fun `patch returns null on HTTP error`() = runTest {
        val r = makeRemote(engine = statusEngine(HttpStatusCode.InternalServerError))
        assertNull(r.patch("{}"))
    }

    @Test fun `patch returns null on IOException`() = runTest {
        val r = makeRemote(engine = MockEngine { throw IOException("network error") })
        assertNull(r.patch("{}"))
    }

    // ── progressFileUrl() ─────────────────────────────────────────────────

    @Test fun `progressFileUrl appends trailing slash when base path lacks one`() {
        assertEquals(
            "https://webdav.example.com/riffle/chitanka__book-123__progress.json",
            WebDavProgressRemote.progressFileUrl("https://webdav.example.com/riffle", "chitanka", "book-123"),
        )
    }

    @Test fun `progressFileUrl preserves existing trailing slash`() {
        assertEquals(
            "https://webdav.example.com/riffle/chitanka__book-123__progress.json",
            WebDavProgressRemote.progressFileUrl("https://webdav.example.com/riffle/", "chitanka", "book-123"),
        )
    }

    @Test fun `progressFileUrl uses double-underscore separator between all three segments`() {
        val url = WebDavProgressRemote.progressFileUrl("https://dav.test/", "gutenberg", "42")
        assertTrue(url.endsWith("gutenberg__42__progress.json"))
    }

    // ADR 0063: Chitanka itemIds contain '/' (e.g. "book/12073-xxx"). %2F in a URL path causes
    // Synology and other WebDAV servers that decode percent-encoding before routing to split the
    // filename on the decoded slash and return 404 (parent directory does not exist). Replaced with
    // '.' which is safe in both URL paths and filesystem filenames, and does not appear in
    // Chitanka/Gutenberg itemIds — no collision risk.
    // Removed-test: progressFileUrl percent-encodes special characters in itemId
    // Removed-test: progressFileUrl encodes spaces as %20 not +
    @Test fun `progressFileUrl replaces slash with dot in itemId`() {
        val url = WebDavProgressRemote.progressFileUrl("https://dav.test/", "chitanka", "book/12073-foo")
        assertTrue("slash in itemId must become dot, not %2F", url.contains("book.12073-foo"))
        assertFalse("must not contain %2F which Synology decodes as path separator", url.contains("%2F"))
        assertTrue(url.endsWith("__progress.json"))
    }

    @Test fun `progressFileUrl replaces slash with dot in namespace`() {
        val url = WebDavProgressRemote.progressFileUrl("https://dav.test/", "ns/sub", "42")
        assertTrue(url.contains("ns.sub"))
        assertFalse(url.contains("%2F"))
    }

    @Test fun `progressFileUrl with audio suffix uses AUDIO_PROGRESS_SUFFIX constant`() {
        val url = WebDavProgressRemote.progressFileUrl(
            "https://dav.test/", "ns", "item",
            WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX,
        )
        assertTrue(url.endsWith(WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX))
    }

    @Test fun `AUDIO_PROGRESS_SUFFIX and EBOOK_PROGRESS_SUFFIX are distinct`() {
        assertTrue(WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX != WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX)
    }

    // ── asAudioRemote() ───────────────────────────────────────────────────

    @Test fun `asAudioRemote get - parses decimal string position as Double`() = runTest {
        val body = """{"position":"942.5","readingProgress":0.3,"finishedAt":null,"lastUpdate":1724000000000}"""
        val r = makeRemote(engine = okEngine(body)).asAudioRemote()
        val result = r.get()
        assertNotNull(result)
        assertEquals(942.5, result!!.position, 0.0001)
        assertEquals(0.3f, result.readingProgress, 0.001f)
    }

    @Test fun `asAudioRemote get - returns 0 dot 0 when position is empty (404 first-sync case)`() = runTest {
        val r = makeRemote(engine = statusEngine(HttpStatusCode.NotFound)).asAudioRemote()
        val result = r.get()
        assertNotNull(result)
        assertEquals(0.0, result!!.position, 0.0001)
        assertEquals(0L, result.lastUpdate)
    }

    @Test fun `asAudioRemote get - returns null on network error`() = runTest {
        val r = makeRemote(engine = MockEngine { throw IOException("network error") }).asAudioRemote()
        assertNull(r.get())
    }

    @Test fun `asAudioRemote patch - converts Double seconds to decimal string`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { req ->
            capturedBody = (req.body as io.ktor.http.content.TextContent).text
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Created, headers = headersOf())
        }
        makeRemote(engine = engine).asAudioRemote().patch(3661.25)
        val payload = WebDavProgressRemote.json
            .decodeFromString(WebDavProgressRemote.ProgressPayload.serializer(), capturedBody)
        assertEquals("3661.25", payload.position)
    }
}
