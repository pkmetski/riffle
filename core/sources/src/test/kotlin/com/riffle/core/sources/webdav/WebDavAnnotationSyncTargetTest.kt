package com.riffle.core.sources.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class WebDavAnnotationSyncTargetTest {

    // ===== Test helpers =====

    private val BASE = "http://dav.test"

    private fun xmlHeaders() = headersOf(HttpHeaders.ContentType, "text/xml")
    private fun textHeaders() = headersOf(HttpHeaders.ContentType, "text/plain")

    private data class Stub(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    private fun buildTarget(
        vararg stubs: Stub,
        username: String = USER,
        password: String = PASS,
        basePath: String = "annotations",
    ): Pair<MockEngine, WebDavAnnotationSyncTarget> {
        val queue = ArrayDeque(stubs.toList())
        val engine = MockEngine { _ ->
            val (body, status) = queue.removeFirst()
            respond(ByteReadChannel(body), status, xmlHeaders())
        }
        val target = WebDavAnnotationSyncTarget(
            baseUrl = Url("$BASE/$basePath"),
            username = username,
            password = password,
            client = HttpClient(engine),
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
        return engine to target
    }

    private fun makeTargetWithFailingClient(throwable: Throwable): WebDavAnnotationSyncTarget {
        val failEngine = MockEngine { throw throwable }
        return WebDavAnnotationSyncTarget(
            baseUrl = Url("https://example.test/dav/"),
            username = "u",
            password = "p",
            client = HttpClient(failEngine),
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
    }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    // ===== read() =====

    @Test fun `read returns body content on 200`() = runTest {
        val (engine, target) = buildTarget(Stub("{\"some\":\"json\"}"))

        val content = target.read("srv1", "book1", "annotations-dev.jsonld")

        assertEquals("{\"some\":\"json\"}", content)
        val req = engine.requestHistory[0]
        assertEquals("GET", req.method.value)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.url.encodedPath)
        assertEquals(basicAuth(USER, PASS), req.headers[HttpHeaders.Authorization])
    }

    @Test fun `read returns null on 404`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.NotFound))

        val content = target.read("srv1", "book1", "annotations-dev.jsonld")

        assertNull(content)
    }

    @Test fun `read throws AuthFailed on 401`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.Unauthorized))

        try {
            target.read("srv1", "book1", "annotations-dev.jsonld")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test fun `read throws AuthFailed on 403`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.Forbidden))

        try {
            target.read("srv1", "book1", "annotations-dev.jsonld")
            fail("expected AuthFailed")
        } catch (_: AnnotationSyncException.AuthFailed) { /* expected */ }
    }

    @Test fun `read returns the file body verbatim`() = runTest {
        val body = """[{"id":"urn:uuid:ä-ø-中"}]"""
        val (_, target) = buildTarget(Stub(body))

        val content = target.read("srv1", "book1", "annotations-dev.jsonld")

        assertEquals(body, content)
    }

    // ===== write() =====

    @Test fun `write PUTs body with auth and JSON-LD content type`() = runTest {
        val (engine, target) = buildTarget(Stub("", HttpStatusCode.Created))

        target.write("srv1", "book1", "annotations-dev.jsonld", "{\"a\":1}")

        val req = engine.requestHistory[0]
        assertEquals("PUT", req.method.value)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.url.encodedPath)
        assertEquals(basicAuth(USER, PASS), req.headers[HttpHeaders.Authorization])
        val ct = req.body.contentType?.toString() ?: ""
        assertTrue("Content-Type should be JSON-LD, was $ct", ct.contains("application/ld+json"))
    }

    @Test fun `write issues a single PUT — flat layout means no MKCOL chain`() = runTest {
        val (engine, target) = buildTarget(Stub("", HttpStatusCode.Created))

        target.write("srv1", "book1", "annotations-dev.jsonld", "x")

        assertEquals(1, engine.requestHistory.size)
        assertEquals("PUT", engine.requestHistory[0].method.value)
    }

    @Test fun `write throws AuthFailed on 401`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.Unauthorized))

        try {
            target.write("srv1", "book1", "annotations-dev.jsonld", "x")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test fun `write throws HttpFailure on a source-side 5xx`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.ServiceUnavailable))

        try {
            target.write("srv1", "book1", "annotations-dev.jsonld", "x")
            fail("expected HttpFailure")
        } catch (e: AnnotationSyncException.HttpFailure) {
            assertEquals(503, e.code)
        }
    }

    // ===== list() =====

    @Test fun `list PROPFINDs basePath and filters by prefix`() = runTest {
        val (engine, target) = buildTarget(Stub(PROPFIND_FLAT_BODY, HttpStatusCode.MultiStatus))

        val files = target.list("srv1", "book1")

        assertEquals(setOf("annotations-dev-a.jsonld", "annotations-dev-b.jsonld"), files.toSet())
        val req = engine.requestHistory[0]
        assertEquals("PROPFIND", req.method.value)
        assertEquals("/annotations/", req.url.encodedPath)
        assertEquals("1", req.headers["Depth"])
    }

    @Test fun `list returns empty when directory absent (404)`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.NotFound))

        assertEquals(emptyList<String>(), target.list("srv1", "book1"))
    }

    @Test fun `list treats 405 the same as 404`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.MethodNotAllowed))

        assertEquals(emptyList<String>(), target.list("srv1", "book1"))
    }

    @Test fun `list treats 400 as empty`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.BadRequest))

        assertEquals(emptyList<String>(), target.list("srv1", "book1"))
    }

    @Test fun `list returns separate filenames for each device`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_MULTI_DEVICE_BODY, HttpStatusCode.MultiStatus))

        val files = target.list("srv1", "book1")

        assertEquals(
            setOf("annotations-device-A.jsonld", "annotations-device-B.jsonld", "annotations-device-C.jsonld"),
            files.toSet(),
        )
    }

    @Test fun `list throws AuthFailed on 401`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.Unauthorized))

        try {
            target.list("srv1", "book1")
            fail("expected AuthFailed")
        } catch (e: AnnotationSyncException.AuthFailed) {
            // expected
        }
    }

    @Test fun `composite filenames cannot collide across books with the same prefix substring`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_PREFIX_COLLISION_BODY, HttpStatusCode.MultiStatus))

        val files = target.list("srv1", "book")

        assertEquals(setOf("annotations-dev.jsonld"), files.toSet())
    }

    // ===== every request carries Finder UA =====

    @Test fun `every request carries a Finder UA`() = runTest {
        val (engine, target) = buildTarget(
            Stub(PROPFIND_EMPTY_BODY, HttpStatusCode.MultiStatus),
            Stub("{\"some\":\"json\"}", HttpStatusCode.OK),
            Stub("", HttpStatusCode.Created),
        )

        target.list("srv1", "book1")
        target.read("srv1", "book1", "annotations-dev.jsonld")
        target.write("srv1", "book1", "annotations-dev.jsonld", "x")

        for (req in engine.requestHistory) {
            val ua = req.headers[HttpHeaders.UserAgent] ?: ""
            assertTrue("expected Finder UA on ${req.method.value} ${req.url.encodedPath}, was \"$ua\"", ua.startsWith("WebDAVFS/"))
        }
    }

    // ===== testConnection() =====

    @Test fun `testConnection returns Success when base PROPFIND ok`() = runTest {
        val (engine, target) = buildTarget(Stub(PROPFIND_EMPTY_BODY, HttpStatusCode.MultiStatus))

        val result = target.testConnection()

        assertEquals(TestConnectionResult.Success, result)
        assertEquals("PROPFIND", engine.requestHistory[0].method.value)
    }

    @Test fun `testConnection MKCOLs base dir on 404 then succeeds`() = runTest {
        val (engine, target) = buildTarget(
            Stub("", HttpStatusCode.NotFound),
            Stub("", HttpStatusCode.Created),
        )

        val result = target.testConnection()

        assertEquals(TestConnectionResult.Success, result)
        assertEquals("PROPFIND", engine.requestHistory[0].method.value)
        assertEquals("MKCOL", engine.requestHistory[1].method.value)
    }

    @Test fun `testConnection returns AuthFailed on 401`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.Unauthorized))

        assertEquals(TestConnectionResult.AuthFailed, target.testConnection())
    }

    @Test fun `testConnection returns ServerError on 5xx`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.ServiceUnavailable))

        assertTrue(target.testConnection() is TestConnectionResult.ServerError)
    }

    @Test fun `testConnection returns NetworkError when host unreachable`() = runTest {
        val target = makeTargetWithFailingClient(java.io.IOException("connection refused"))

        assertTrue(target.testConnection() is TestConnectionResult.NetworkError)
    }

    // ===== delete() =====

    @Test fun `delete issues a DELETE on the composite path with auth`() = runTest {
        val (engine, target) = buildTarget(Stub("", HttpStatusCode.NoContent))

        target.delete("srv1", "book1", "annotations-dev.jsonld")

        val req = engine.requestHistory[0]
        assertEquals("DELETE", req.method.value)
        assertEquals("/annotations/srv1__book1__annotations-dev.jsonld", req.url.encodedPath)
        assertEquals(basicAuth(USER, PASS), req.headers[HttpHeaders.Authorization])
    }

    @Test fun `delete is a no-op on 404 (file already gone)`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.NotFound))

        // Should not throw.
        target.delete("srv1", "book1", "annotations-dev.jsonld")
    }

    // ===== readDeviceMeta / writeDeviceMeta =====

    @Test fun `readDeviceMeta GETs the namespace-scoped sentinel path`() = runTest {
        val (engine, target) = buildTarget(Stub("""{"type":"riffle:DeviceSyncMeta"}"""))

        val body = target.readDeviceMeta("srv1", "dev-A")

        assertNotNull(body)
        assertTrue(body!!.contains("riffle:DeviceSyncMeta"))
        assertEquals("/annotations/srv1__device-meta-dev-A.json", engine.requestHistory[0].url.encodedPath)
    }

    @Test fun `readDeviceMeta returns null on 404`() = runTest {
        val (_, target) = buildTarget(Stub("", HttpStatusCode.NotFound))

        assertNull(target.readDeviceMeta("srv1", "dev-A"))
    }

    @Test fun `writeDeviceMeta PUTs to the namespace-scoped sentinel path`() = runTest {
        val (engine, target) = buildTarget(Stub("", HttpStatusCode.Created))

        target.writeDeviceMeta("srv1", "dev-A", """{"type":"riffle:DeviceSyncMeta"}""")

        val req = engine.requestHistory[0]
        assertEquals("PUT", req.method.value)
        assertEquals("/annotations/srv1__device-meta-dev-A.json", req.url.encodedPath)
    }

    // ===== enumerateDevices() =====

    @Test fun `enumerateDevices ignores device-meta files at the namespace root`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_WITH_DEVICE_META_BODY, HttpStatusCode.MultiStatus))

        val listing = target.enumerateDevices("srv1")

        assertEquals(1, listing.devices.size)
        assertEquals("dev-A", listing.devices.single().deviceId)
    }

    // ===== enumerateNamespaces() =====

    @Test fun `enumerateNamespaces groups files by namespace prefix and counts annotations`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_MIXED_NAMESPACES_BODY, HttpStatusCode.MultiStatus))

        val result = target.enumerateNamespaces()

        assertEquals(2, result.size)
        val ns1 = result.first { it.namespace == "ns-1" }
        val ns2 = result.first { it.namespace == "ns-2" }
        assertEquals(2, ns1.annotationFileCount)
        assertEquals(1, ns2.annotationFileCount)
    }

    @Test fun `enumerateNamespaces skips Synology AppleDouble shadow files`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_APPLEDOUBLE_BODY, HttpStatusCode.MultiStatus))

        val result = target.enumerateNamespaces()

        assertEquals(1, result.size)
        assertEquals("ns-1", result.first().namespace)
    }

    // ===== forgetNamespace() =====

    @Test fun `forgetNamespace DELETEs every file matching the prefix`() = runTest {
        val (engine, target) = buildTarget(
            Stub(PROPFIND_MIXED_NAMESPACES_BODY, HttpStatusCode.MultiStatus),
            Stub("", HttpStatusCode.NoContent),
            Stub("", HttpStatusCode.NoContent),
            Stub("", HttpStatusCode.NoContent),
        )

        val deleted = target.forgetNamespace("ns-1")

        assertEquals(3, deleted)
        assertEquals("PROPFIND", engine.requestHistory[0].method.value)
        val deletePaths = engine.requestHistory.drop(1).map { it.url.encodedPath }
        assertTrue(deletePaths.all { it.startsWith("/annotations/ns-1__") })
        assertFalse(deletePaths.any { it.contains("ns-2") })
    }

    @Test fun `list skips Synology AppleDouble shadow files`() = runTest {
        val (_, target) = buildTarget(Stub(PROPFIND_APPLEDOUBLE_BODY, HttpStatusCode.MultiStatus))

        val result = target.list("ns-1", "book1")

        assertEquals(listOf("annotations-dev.jsonld"), result)
    }

    // ===== error propagation =====

    @Test fun `list wraps IOException as NetworkError`() = runTest {
        val target = makeTargetWithFailingClient(java.io.IOException("connection reset"))

        try {
            target.list("namespace-1", "item-1")
            fail("expected NetworkError")
        } catch (e: AnnotationSyncException.NetworkError) {
            assertTrue(e.message!!.contains("connection reset"))
        }
    }

    @Test fun `list wraps SSLException as TlsError`() = runTest {
        val target = makeTargetWithFailingClient(javax.net.ssl.SSLException("cert untrusted"))

        try {
            target.list("namespace-1", "item-1")
            fail("expected TlsError")
        } catch (e: AnnotationSyncException.TlsError) {
            assertTrue(e.message!!.contains("cert untrusted"))
        }
    }

    // ===== Legacy ABS namespace migration =====

    @Test fun `list MOVEs legacy bare-UUID files to abs_ prefixed names`() = runTest {
        val (engine, target) = buildTarget(
            Stub(PROPFIND_LEGACY_ABS_BODY, HttpStatusCode.MultiStatus),
            Stub("", HttpStatusCode.Created), // MOVE of the legacy file
        )

        val result = target.list("abs_$ABS_UUID", "book1")

        assertEquals(
            setOf("annotations-dev-legacy.jsonld", "annotations-dev-fresh.jsonld"),
            result.toSet(),
        )
        val move = engine.requestHistory[1]
        assertEquals("MOVE", move.method.value)
        assertEquals("/annotations/${ABS_UUID}__book1__annotations-dev-legacy.jsonld", move.url.encodedPath)
        val dest = move.headers["Destination"] ?: ""
        assertTrue(
            "Destination must point at the abs_ path, was $dest",
            dest.endsWith("/annotations/abs_${ABS_UUID}__book1__annotations-dev-legacy.jsonld"),
        )
        assertEquals("F", move.headers["Overwrite"])
    }

    @Test fun `already-migrated share is a no-op — no MOVE issued`() = runTest {
        val (engine, target) = buildTarget(Stub(PROPFIND_ALREADY_MIGRATED_BODY, HttpStatusCode.MultiStatus))

        target.list("abs_$ABS_UUID", "book1")

        assertEquals("only PROPFIND should have fired", 1, engine.requestHistory.size)
    }

    @Test fun `MOVE returning 404 still surfaces the file under the new namespace`() = runTest {
        val (_, target) = buildTarget(
            Stub(PROPFIND_LEGACY_ABS_BODY, HttpStatusCode.MultiStatus),
            Stub("", HttpStatusCode.NotFound), // MOVE — source already gone
        )

        val result = target.list("abs_$ABS_UUID", "book1")

        assertEquals(
            setOf("annotations-dev-legacy.jsonld", "annotations-dev-fresh.jsonld"),
            result.toSet(),
        )
    }

    @Test fun `MOVE returning 412 triggers DELETE of the legacy source and dedupes the list`() = runTest {
        val (engine, target) = buildTarget(
            Stub(PROPFIND_ORPHAN_LEGACY_BODY, HttpStatusCode.MultiStatus),
            Stub("", HttpStatusCode.PreconditionFailed), // MOVE — destination exists
            Stub("", HttpStatusCode.NoContent),          // DELETE of orphan source
        )

        val result = target.list("abs_$ABS_UUID", "book1")

        assertEquals(listOf("annotations-dev-shared.jsonld"), result)
        val delete = engine.requestHistory[2]
        assertEquals("DELETE", delete.method.value)
        assertEquals(
            "/annotations/${ABS_UUID}__book1__annotations-dev-shared.jsonld",
            delete.url.encodedPath,
        )
    }

    @Test fun `komga files on the share are left alone by the ABS migration`() = runTest {
        val (engine, target) = buildTarget(
            Stub(PROPFIND_ABS_AND_KOMGA_BODY, HttpStatusCode.MultiStatus),
            Stub("", HttpStatusCode.Created), // MOVE of the ABS legacy file only
        )

        target.list("abs_$ABS_UUID", "book1")

        val move = engine.requestHistory[1]
        assertEquals("MOVE", move.method.value)
        assertTrue(
            "must migrate the ABS file, not the komga file",
            move.url.encodedPath.startsWith("/annotations/${ABS_UUID}__"),
        )
        assertEquals("no second MOVE for the komga file", 2, engine.requestHistory.size)
    }

    companion object {
        private const val USER = "alice"
        private const val PASS = "s3cret"
        private const val ABS_UUID = "19621aae-1111-2222-3333-4a4a4a4a4a4a"

        private val PROPFIND_LEGACY_ABS_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/${ABS_UUID}__book1__annotations-dev-legacy.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/abs_${ABS_UUID}__book1__annotations-dev-fresh.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        private val PROPFIND_ALREADY_MIGRATED_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/abs_${ABS_UUID}__book1__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        private val PROPFIND_ORPHAN_LEGACY_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/${ABS_UUID}__book1__annotations-dev-shared.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/abs_${ABS_UUID}__book1__annotations-dev-shared.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        private val PROPFIND_ABS_AND_KOMGA_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/annotations/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/${ABS_UUID}__book1__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/annotations/komga_${ABS_UUID}__book1__annotations-dev.jsonld</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

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
