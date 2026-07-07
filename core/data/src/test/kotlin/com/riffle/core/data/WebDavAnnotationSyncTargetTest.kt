package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class WebDavAnnotationSyncTargetTest {

    private lateinit var source: MockWebServer

    @Before
    fun setUp() {
        source = MockWebServer()
        source.start()
    }

    @After
    fun tearDown() {
        source.shutdown()
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
            baseUrl = source.url("/$basePath"),
            username = username,
            password = password,
            client = client,
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
    }

    @Test
    fun `read returns body content on 200`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody("{\"some\":\"json\"}"))

        val content = newTarget().read("srv1", "book1", "annotations-dev.jsonld")

        assertEquals("{\"some\":\"json\"}", content)
        val req = source.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(basicAuth(USER, PASS), req.getHeader("Authorization"))
    }

    @Test
    fun `every request carries a Finder UA — Synology gates writes on User-Agent`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_EMPTY_BODY))
        source.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        source.enqueue(MockResponse().setResponseCode(201))

        val target = newTarget()
        target.list("srv1", "book1")
        target.read("srv1", "book1", "annotations-dev.jsonld")
        target.write("srv1", "book1", "annotations-dev.jsonld", "x")

        repeat(3) {
            val req = source.takeRequest()
            val ua = req.getHeader("User-Agent") ?: ""
            assertTrue("expected Finder UA on ${req.method} ${req.path}, was \"$ua\"", ua.startsWith("WebDAVFS/"))
        }
    }

    @Test
    fun `read returns null on 404`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404))

        val content = newTarget().read("srv1", "book1", "annotations-dev.jsonld")

        assertNull(content)
    }

    @Test
    fun `read throws AuthFailed on 401`() = runTest {
        source.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().read("srv1", "book1", "annotations-dev.jsonld")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `write PUTs body with auth and content type`() = runTest {
        source.enqueue(MockResponse().setResponseCode(201))

        newTarget().write("srv1", "book1", "annotations-dev.jsonld", "{\"a\":1}")

        val req = source.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(basicAuth(USER, PASS), req.getHeader("Authorization"))
        assertEquals("{\"a\":1}", req.body.readUtf8())
        val ct = req.getHeader("Content-Type") ?: ""
        assertTrue("Content-Type should be JSON-LD, was $ct", ct.contains("application/ld+json"))
    }

    @Test
    fun `write issues a single PUT — flat layout means no MKCOL chain`() = runTest {
        source.enqueue(MockResponse().setResponseCode(201))

        newTarget().write("srv1", "book1", "annotations-dev.jsonld", "x")

        val req = source.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(1, source.requestCount)
    }

    @Test
    fun `write throws AuthFailed on 401`() = runTest {
        source.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().write("srv1", "book1", "annotations-dev.jsonld", "x")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `list PROPFINDs basePath, filters by prefix, and strips it from returned names`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_FLAT_BODY))

        val files = newTarget().list("srv1", "book1")

        // Fixture has two files for srv1/book1 (returned) and one for srv1/book2 (filtered).
        assertEquals(
            setOf("annotations-dev-a.jsonld", "annotations-dev-b.jsonld"),
            files.toSet(),
        )
        val req = source.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/annotations/", req.path)
        assertEquals("1", req.getHeader("Depth"))
    }

    @Test
    fun `list returns empty when directory absent (404)`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404))

        val files = newTarget().list("srv1", "book1")

        assertEquals(emptyList<String>(), files)
    }

    @Test
    fun `list treats 405 the same as 404 (Synology returns 405 for non-existent dirs)`() = runTest {
        source.enqueue(MockResponse().setResponseCode(405))

        val files = newTarget().list("srv1", "book1")

        assertEquals(emptyList<String>(), files)
    }

    @Test
    fun `list treats 400 as empty (Synology returns 400 for non-existent deep paths)`() = runTest {
        source.enqueue(MockResponse().setResponseCode(400))

        val files = newTarget().list("srv1", "book1")

        assertEquals(emptyList<String>(), files)
    }

    @Test
    fun `list returns separate filenames for each device that wrote to this book`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_MULTI_DEVICE_BODY))

        val files = newTarget().list("srv1", "book1")

        // Three different devices wrote files for srv1/book1; one unrelated file for srv1/book2.
        assertEquals(
            setOf(
                "annotations-device-A.jsonld",
                "annotations-device-B.jsonld",
                "annotations-device-C.jsonld",
            ),
            files.toSet(),
        )
    }

    @Test
    fun `read returns the file's body verbatim (UTF-8, preserves non-ASCII)`() = runTest {
        val body = """[{"id":"urn:uuid:ä-ø-中"}]"""
        source.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val content = newTarget().read("srv1", "book1", "annotations-dev.jsonld")

        assertEquals(body, content)
    }

    @Test
    fun `read throws AuthFailed on 403`() = runTest {
        source.enqueue(MockResponse().setResponseCode(403))

        try {
            newTarget().read("srv1", "book1", "annotations-dev.jsonld")
            fail("expected AuthFailed")
        } catch (_: AnnotationSyncException.AuthFailed) { /* expected */ }
    }

    @Test
    fun `write throws HttpFailure on a source-side 5xx`() = runTest {
        source.enqueue(MockResponse().setResponseCode(503))

        try {
            newTarget().write("srv1", "book1", "annotations-dev.jsonld", "x")
            fail("expected HttpFailure")
        } catch (e: AnnotationSyncException.HttpFailure) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun `composite filenames cannot collide across books with the same prefix substring`() = runTest {
        // "book" and "book-2" both start with "book"; the `__` separator guarantees that the
        // book-1 list call doesn't accidentally include book-2's file.
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_PREFIX_COLLISION_BODY))

        val files = newTarget().list("srv1", "book")

        assertEquals(setOf("annotations-dev.jsonld"), files.toSet())
    }

    @Test
    fun `list throws AuthFailed on 401`() = runTest {
        source.enqueue(MockResponse().setResponseCode(401))

        try {
            newTarget().list("srv1", "book1")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test
    fun `testConnection returns Success when base PROPFIND ok`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_EMPTY_BODY))

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.Success, result)
        val req = source.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/annotations/", req.path)
    }

    @Test
    fun `testConnection MKCOLs base dir on 404 then succeeds`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404))           // PROPFIND base
        source.enqueue(MockResponse().setResponseCode(201))           // MKCOL base

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.Success, result)
        val r1 = source.takeRequest()
        val r2 = source.takeRequest()
        assertEquals("PROPFIND", r1.method)
        assertEquals("MKCOL", r2.method)
        assertEquals("/annotations/", r2.path)
    }

    @Test
    fun `testConnection returns AuthFailed on 401`() = runTest {
        source.enqueue(MockResponse().setResponseCode(401))

        val result = newTarget().testConnection()

        assertEquals(TestConnectionResult.AuthFailed, result)
    }

    @Test
    fun `testConnection returns ServerError on 5xx`() = runTest {
        source.enqueue(MockResponse().setResponseCode(503))

        val result = newTarget().testConnection()

        assertTrue("expected ServerError, got $result", result is TestConnectionResult.ServerError)
    }

    @Test
    fun `testConnection returns NetworkError when host unreachable`() = runTest {
        // Shut down the source before the call so the connection is refused.
        val target = newTarget()
        source.shutdown()

        val result = target.testConnection()

        assertTrue("expected NetworkError, got $result", result is TestConnectionResult.NetworkError)
    }

    @Test
    fun `list wraps IOException as NetworkError`() = runTest {
        val target = makeTargetWithFailingClient(java.io.IOException("connection reset"))
        try {
            target.list("namespace-1", "item-1")
            fail("expected NetworkError")
        } catch (e: AnnotationSyncException.NetworkError) {
            assertTrue(e.message!!.contains("connection reset"))
        }
    }

    @Test
    fun `list wraps SSLException as TlsError`() = runTest {
        val target = makeTargetWithFailingClient(javax.net.ssl.SSLException("cert untrusted"))
        try {
            target.list("namespace-1", "item-1")
            fail("expected TlsError")
        } catch (e: AnnotationSyncException.TlsError) {
            assertTrue(e.message!!.contains("cert untrusted"))
        }
    }

    private fun makeTargetWithFailingClient(throwable: Throwable): WebDavAnnotationSyncTarget {
        val failingInterceptor = okhttp3.Interceptor { throw throwable }
        val client = OkHttpClient.Builder().addInterceptor(failingInterceptor).build()
        return WebDavAnnotationSyncTarget(
            baseUrl = "https://example.test/dav/".toHttpUrl(),
            username = "u",
            password = "p",
            client = client,
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
    }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun `delete issues a DELETE on the composite path with auth`() = runTest {
        source.enqueue(MockResponse().setResponseCode(204))

        newTarget().delete("srv1", "book1", "annotations-dev.jsonld")

        val req = source.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.path)
        assertEquals(basicAuth(USER, PASS), req.getHeader("Authorization"))
    }

    @Test
    fun `delete is a no-op on 404 (file already gone)`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404))
        // Should not throw.
        newTarget().delete("srv1", "book1", "annotations-dev.jsonld")
    }

    @Test
    fun `readDeviceMeta GETs the namespace-scoped sentinel path`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody("""{"type":"riffle:DeviceSyncMeta","deviceId":"dev-A","label":"L","lastSyncedAt":"2026-06-27T12:00:00Z"}"""))

        val body = newTarget().readDeviceMeta("srv1", "dev-A")

        assertNotNull(body)
        assertTrue(body!!.contains("riffle:DeviceSyncMeta"))
        val req = source.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/annotations/srv1__device-meta-dev-A.json", req.path)
    }

    @Test
    fun `readDeviceMeta returns null on 404`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404))
        assertNull(newTarget().readDeviceMeta("srv1", "dev-A"))
    }

    @Test
    fun `writeDeviceMeta PUTs to the namespace-scoped sentinel path`() = runTest {
        source.enqueue(MockResponse().setResponseCode(201))

        newTarget().writeDeviceMeta("srv1", "dev-A", """{"type":"riffle:DeviceSyncMeta","deviceId":"dev-A","label":"L","lastSyncedAt":"now"}""")

        val req = source.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/annotations/srv1__device-meta-dev-A.json", req.path)
        assertTrue(req.body.readUtf8().contains("riffle:DeviceSyncMeta"))
    }

    @Test
    fun `enumerateDevices ignores device-meta files at the namespace root`() = runTest {
        // PROPFIND surfaces device-meta files too; the device-listing must not invent a phantom
        // device row from one, otherwise Maintenance would show duplicates.
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_WITH_DEVICE_META_BODY))

        val listing = newTarget().enumerateDevices("srv1")

        // Only the real annotation file contributes a row — the device-meta-A.json sibling is
        // not annotation content and must not surface as its own device.
        assertEquals(1, listing.devices.size)
        assertEquals("dev-A", listing.devices.single().deviceId)
    }

    @Test
    fun `enumerateNamespaces groups files by namespace prefix and counts annotations`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_MIXED_NAMESPACES_BODY))

        val result = newTarget().enumerateNamespaces()

        assertEquals(2, result.size)
        val ns1 = result.first { it.namespace == "ns-1" }
        val ns2 = result.first { it.namespace == "ns-2" }
        // Only .jsonld files count; the legacy `ns-1__device-dev-a.json` sidecar in the fixture
        // is an unknown-shape file under base and is skipped silently.
        assertEquals(2, ns1.annotationFileCount)
        assertEquals(1, ns2.annotationFileCount)
    }

    @Test
    fun `enumerateNamespaces skips Synology AppleDouble shadow files`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_APPLEDOUBLE_BODY))

        val result = newTarget().enumerateNamespaces()

        // Only the real namespace must be reported — the `._` shadow file must not spawn a phantom one.
        assertEquals(1, result.size)
        assertEquals("ns-1", result.first().namespace)
    }

    @Test
    fun `forgetNamespace DELETEs every file matching the prefix`() = runTest {
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_MIXED_NAMESPACES_BODY))
        // 3 files match ns-1 (2 jsonld + 1 unknown-shape json the fixture happens to include);
        // forgetNamespace deletes by prefix regardless of suffix. The 1 ns-2 file must NOT be DELETEd.
        repeat(3) { source.enqueue(MockResponse().setResponseCode(204)) }

        val deleted = newTarget().forgetNamespace("ns-1")

        assertEquals(3, deleted)
        // First request was the PROPFIND, then 3 DELETEs in some order.
        assertEquals("PROPFIND", source.takeRequest().method)
        val deletePaths = mutableListOf<String>()
        repeat(3) {
            val req = source.takeRequest()
            assertEquals("DELETE", req.method)
            deletePaths += req.path!!
        }
        assertTrue(deletePaths.all { it.startsWith("/annotations/ns-1__") })
        assertFalse(deletePaths.any { it.contains("ns-2") })
    }

    @Test
    fun `list skips Synology AppleDouble shadow files`() = runTest {
        // The list path is the same propfind parser, so make sure it doesn't surface the `._` shadow.
        source.enqueue(MockResponse().setResponseCode(207).setBody(PROPFIND_APPLEDOUBLE_BODY))

        val result = newTarget().list("ns-1", "book1")

        // Only the real annotation file matters; the `._` shadow must be filtered out.
        assertEquals(listOf("annotations-dev.jsonld"), result)
    }

    companion object {
        private const val USER = "alice"
        private const val PASS = "s3cret"

        // PROPFIND fixture for the flat layout: two srv1__book1__ files (matching), one srv1__book2__
        // file (must be filtered out by prefix), plus the parent collection entry.
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

        // Three devices have written to srv1/book1; one unrelated file belongs to srv1/book2.
        private val PROPFIND_MULTI_DEVICE_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-device-A.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-device-B.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-device-C.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book2__annotations-device-A.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        // PROPFIND that includes one annotation file *and* its sibling per-device sentinel,
        // both under `srv1`. enumerateDevices must surface only the annotation file's owner.
        private val PROPFIND_WITH_DEVICE_META_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book1__annotations-dev-A.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__device-meta-dev-A.json</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        // Two namespaces side-by-side: ns-1 has two annotation files + one sidecar; ns-2 has one
        // annotation file only.
        private val PROPFIND_MIXED_NAMESPACES_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/ns-1__book1__annotations-dev-a.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/ns-1__book2__annotations-dev-a.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/ns-1__device-dev-a.json</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/ns-2__book1__annotations-dev-x.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        // Real annotation file + Synology AppleDouble shadow + a `._<ns>__…` shadow that previously
        // showed up as a phantom namespace in the Maintenance UI.
        private val PROPFIND_APPLEDOUBLE_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/ns-1__book1__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/._ns-1__book1__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        // "book" and "book-2" share the same string prefix; the `__` separator must keep them apart.
        private val PROPFIND_PREFIX_COLLISION_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/srv1__book-2__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}

