package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class WebDavAnnotationSyncTargetTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newTarget(
        username: String = USER,
        password: String = PASS,
        basePath: String = "annotations",
    ): WebDavAnnotationSyncTarget {
        val client = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        return WebDavAnnotationSyncTarget(
            baseUrl = server.url("/$basePath"),
            username = username,
            password = password,
            client = client,
        )
    }

    @Test
    fun `read returns body content on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"some\":\"json\"}"))

        val content = newTarget().read("srv1", "book1", "annotations-dev.jsonld")

        assertEquals("{\"some\":\"json\"}", content)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(basicAuth(USER, PASS), req.getHeader("Authorization"))
    }

    @Test
    fun `read returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val content = newTarget().read("srv1", "book1", "annotations-dev.jsonld")

        assertNull(content)
    }

    @Test
    fun `read throws AuthFailed on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().read("srv1", "book1", "annotations-dev.jsonld")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `write PUTs body with auth and content type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))

        newTarget().write("srv1", "book1", "annotations-dev.jsonld", "{\"a\":1}")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(basicAuth(USER, PASS), req.getHeader("Authorization"))
        assertEquals("{\"a\":1}", req.body.readUtf8())
        val ct = req.getHeader("Content-Type") ?: ""
        assertTrue("Content-Type should be JSON-LD, was $ct", ct.contains("application/ld+json"))
    }

    @Test
    fun `write issues a single PUT — flat layout avoids MKCOL entirely`() = runTest {
        // Flat-layout writes never need to create per-book subdirectories, so even a "fresh"
        // server only sees the one PUT (the user pre-creates basePath via Test Connection).
        server.enqueue(MockResponse().setResponseCode(201))

        newTarget().write("srv1", "book1", "annotations-dev.jsonld", "x")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `write throws AuthFailed on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().write("srv1", "book1", "annotations-dev.jsonld", "x")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `list issues PROPFIND with depth 1 against basePath and returns matching files`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(PROPFIND_FLAT_BODY),
        )

        val files = newTarget().list("srv1", "book1")

        assertEquals(
            setOf("annotations-dev-a.jsonld", "annotations-dev-b.jsonld"),
            files.toSet(),
        )
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/annotations/", req.path)
        assertEquals("1", req.getHeader("Depth"))
    }

    @Test
    fun `list returns empty when directory absent (404)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val files = newTarget().list("srv1", "book1")

        assertEquals(emptyList<String>(), files)
    }

    @Test
    fun `list treats 405 the same as 404 (Synology returns 405 for non-existent dirs)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405))

        val files = newTarget().list("srv1", "book1")

        assertEquals(emptyList<String>(), files)
    }

    @Test
    fun `list filters by serverId itemId prefix and strips it from returned names`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_FLAT_BODY))

        val files = newTarget().list("srv1", "book1")

        // The fixture lists three files: two for srv1/book1 (returned) plus one for srv1/book2
        // (a different book — must be filtered out).
        assertEquals(
            setOf("annotations-dev-a.jsonld", "annotations-dev-b.jsonld"),
            files.toSet(),
        )
    }

    @Test
    fun `list throws AuthFailed on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().list("srv1", "book1")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `testConnection returns Success when base PROPFIND ok`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_EMPTY_BODY))

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.Success, result)
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/annotations/", req.path)
    }

    @Test
    fun `testConnection MKCOLs base dir on 404 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))           // PROPFIND base
        server.enqueue(MockResponse().setResponseCode(201))           // MKCOL base

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.Success, result)
        val r1 = server.takeRequest()
        val r2 = server.takeRequest()
        assertEquals("PROPFIND", r1.method)
        assertEquals("MKCOL", r2.method)
        assertEquals("/annotations/", r2.path)
    }

    @Test
    fun `testConnection returns AuthFailed on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.AuthFailed, result)
    }

    @Test
    fun `testConnection returns ServerError on 5xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = newTarget().testConnection()

        assertTrue("expected ServerError, got $result", result is TestConnectionResult.ServerError)
    }

    @Test
    fun `testConnection returns NetworkError when host unreachable`() = runTest {
        // Shut down the server before the call so the connection is refused.
        val target = newTarget()
        server.shutdown()

        val result = target.testConnection()

        assertTrue("expected NetworkError, got $result", result is TestConnectionResult.NetworkError)
    }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    companion object {
        private const val USER = "alice"
        private const val PASS = "s3cret"

        // A PROPFIND multistatus body covering the flat layout: two files for srv1/book1
        // (matching prefix), one file for srv1/book2 (different book, must be filtered out).
        private val PROPFIND_FLAT_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-dev-a.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-dev-b.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book2__annotations-dev-c.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        private val PROPFIND_EMPTY_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}

