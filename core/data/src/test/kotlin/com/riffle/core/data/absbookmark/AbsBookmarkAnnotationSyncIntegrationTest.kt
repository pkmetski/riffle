package com.riffle.core.data.absbookmark

import com.riffle.core.data.AnnotationFilenames
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration test — real [AbsApiClient] speaks HTTP against a scripted [MockWebServer] that
 * emulates the ABS `bookmark` surface. Verifies the full pipeline: chunk codec → target →
 * `AbsBookmarkApi` wire encoding → OkHttp → MockWebServer, then decode back the other way.
 *
 * Runs everywhere. Excluded from the default `./gradlew test` task by the `*IntegrationTest`
 * name pattern (see `core/data/build.gradle.kts` `testOptions` — `-PintegrationTests` includes it,
 * matching the `integration-test` GHA job). No `Assume` / silent-skip: if the assertions here
 * fail, CI turns red.
 *
 * The mock's behaviour mirrors the real ABS server's observed contract (empirical probe results
 * folded into ADR 0047):
 * - `(libraryItemId, timeSec)` is the primary key; POST at an existing slot updates in place.
 * - DELETE at a nonexistent slot returns HTTP 200 body `"OK"` (idempotent).
 * - Titles can be arbitrarily long; `timeSec` accepts negative values.
 * - `GET /api/me` returns the user object with an embedded `bookmarks` array.
 */
class AbsBookmarkAnnotationSyncIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var abs: AbsApiClient
    private lateinit var absHandler: FakeAbsHandler

    private val itemId = "book-e2e-1"
    private val namespace = "abs_test-user"
    private val deviceA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val deviceB = "d4a2c5e7-1b7f-4d3e-8c1a-2b6f4d3e5c1a"

    @Before
    fun setUp() {
        server = MockWebServer()
        absHandler = FakeAbsHandler()
        server.dispatcher = absHandler
        server.start()
        abs = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun target(): AbsBookmarkAnnotationSyncTarget = AbsBookmarkAnnotationSyncTarget(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "fake-token",
        insecureAllowed = false,
        accountNamespace = namespace,
        api = abs,
        listingCacheTtlMs = 0L, // disable cache in this test so we can assert per-op HTTP behaviour
    )

    @Test
    fun `write then read round-trips through real HTTP`() = runBlocking {
        val t = target()
        val payload = """[{"id":"urn:e2e:1","type":"Annotation","note":"hello A"}]"""
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), payload)
        val readback = t.read(namespace, itemId, AnnotationFilenames.forDevice(deviceA))
        assertEquals(payload, readback)
    }

    @Test
    fun `list then read for another device round-trips`() = runBlocking {
        val t = target()
        val payloadA = """[{"id":"urn:e2e:A"}]"""
        val payloadB = """[{"id":"urn:e2e:B"}]"""
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), payloadA)
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceB), payloadB)

        val listed = t.list(namespace, itemId).toSet()
        assertEquals(
            setOf(
                AnnotationFilenames.forDevice(deviceA),
                AnnotationFilenames.forDevice(deviceB),
            ),
            listed,
        )
        assertEquals(payloadA, t.read(namespace, itemId, AnnotationFilenames.forDevice(deviceA)))
        assertEquals(payloadB, t.read(namespace, itemId, AnnotationFilenames.forDevice(deviceB)))
    }

    @Test
    fun `enumerateDevices recovers full deviceId from manifest across HTTP`() = runBlocking {
        val t = target()
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        t.write(namespace, "book-2", AnnotationFilenames.forDevice(deviceA), """[{"id":"a2"}]""")
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceB), """[{"id":"b"}]""")
        val listing = t.enumerateDevices(namespace)
        val byId = listing.devices.associate { it.deviceId to it.annotationFiles.map { f -> f.itemId }.toSet() }
        assertEquals(setOf(itemId, "book-2"), byId[deviceA])
        assertEquals(setOf(itemId), byId[deviceB])
    }

    @Test
    fun `shrinking payload GCs trailing chunks over HTTP`() = runBlocking {
        val t = target()
        val big = highEntropyPayload(400_000)
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), big)
        val beforeShrink = absHandler.bookmarksFor(itemId).size
        assertTrue("precondition: multi-chunk", beforeShrink >= 3)
        val small = """[{"id":"x"}]"""
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), small)
        // Manifest + one payload chunk.
        assertEquals(2, absHandler.bookmarksFor(itemId).size)
        assertEquals(small, t.read(namespace, itemId, AnnotationFilenames.forDevice(deviceA)))
    }

    @Test
    fun `forgetNamespace leaves foreign bookmarks untouched over HTTP`() = runBlocking {
        val t = target()
        // Preseed foreign bookmarks: yaabsa-style (time=-1) and a real audio bookmark.
        absHandler.addForeign(itemId = itemId, timeSec = -1, title = """[{"cfi":"…"}]""")
        absHandler.addForeign(itemId = itemId, timeSec = 300, title = "Nice quote")

        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        val deleted = t.forgetNamespace(namespace)
        assertTrue("expected at least 2 deletions (manifest + 1 chunk), got $deleted", deleted >= 2)

        // Foreign bookmarks preserved byte-exact.
        val remaining = absHandler.bookmarksFor(itemId)
        assertTrue("yaabsa bookmark preserved", remaining.any { it.timeSec == -1 })
        assertTrue("audio bookmark preserved", remaining.any { it.timeSec == 300 })
        // None of our reserved-range bookmarks remain.
        assertEquals(0, remaining.count { AbsBookmarkChunkCodec.parseTimeSlot(it.timeSec) != null })
    }

    @Test
    fun `delete over HTTP tolerates 404 from server`() = runBlocking {
        // Enable "return HTTP 404 on delete of nonexistent" behaviour — some ABS deployments /
        // reverse proxies actually do this. Port contract: MUST NOT throw.
        absHandler.deleteReturns404OnMiss = true
        val t = target()
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        // Bypass the target and nuke the bookmarks so the target's delete sees empty slots.
        absHandler.clear(itemId)
        // No throw — this is the assertion.
        t.delete(namespace, itemId, AnnotationFilenames.forDevice(deviceA))
    }

    @Test
    fun `foreign bookmarks in the reserved range are ignored on read`() = runBlocking {
        // Simulate a hypothetical third-party client that mistakenly writes into our reserved
        // negative-time range with a different prefix. The collision-safety guard says we must
        // ignore, not decode, and definitely not overwrite silently on our next write.
        val t = target()
        absHandler.addForeign(
            itemId = itemId,
            timeSec = AbsBookmarkChunkCodec.TIME_BASE - 1234,
            title = "yaabsa:v99:someone-elses:0:aabbccdd:ZZZZ",
        )
        val payload = """[{"id":"ours"}]"""
        t.write(namespace, itemId, AnnotationFilenames.forDevice(deviceA), payload)
        assertEquals(payload, t.read(namespace, itemId, AnnotationFilenames.forDevice(deviceA)))
        // Foreign bookmark still there.
        assertNotNull(absHandler.bookmarksFor(itemId).firstOrNull { it.timeSec == AbsBookmarkChunkCodec.TIME_BASE - 1234 })
    }

    @Test
    fun `reading a book with no bookmarks returns null cleanly`() = runBlocking {
        val t = target()
        assertNull(t.read(namespace, "empty-book", AnnotationFilenames.forDevice(deviceA)))
        assertEquals(emptyList<String>(), t.list(namespace, "empty-book"))
    }

    private fun highEntropyPayload(size: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var seed = 0x1234567890ABCDEFL
        return buildString(size) {
            repeat(size) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                append(alphabet[((seed ushr 33).toInt() and 63)])
            }
        }
    }
}

/**
 * Scripts a subset of the ABS API on [MockWebServer], modelling `bookmarks` state per user.
 * Mirrors the real server's observed contract (see class kdoc on [AbsBookmarkAnnotationSyncIntegrationTest]).
 */
private class FakeAbsHandler : Dispatcher() {

    data class Bookmark(val libraryItemId: String, val timeSec: Int, val title: String, val createdAt: Long)

    private val state: MutableList<Bookmark> = mutableListOf()
    var deleteReturns404OnMiss: Boolean = false

    fun bookmarksFor(itemId: String): List<Bookmark> = state.filter { it.libraryItemId == itemId }
    fun clear(itemId: String) { state.removeAll { it.libraryItemId == itemId } }
    fun addForeign(itemId: String, timeSec: Int, title: String) {
        state.add(Bookmark(itemId, timeSec, title, createdAt = 0L))
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            request.method == "GET" && path == "/api/me" -> handleMe()
            request.method == "POST" && path.matches(Regex("/api/me/item/[^/]+/bookmark")) ->
                handleCreate(itemFromPath(path), request)
            request.method == "PATCH" && path.matches(Regex("/api/me/item/[^/]+/bookmark")) ->
                handleUpdate(itemFromPath(path), request)
            request.method == "DELETE" && path.matches(Regex("/api/me/item/[^/]+/bookmark/-?\\d+")) ->
                handleDelete(itemFromPath(path), timeFromPath(path))
            else -> MockResponse().setResponseCode(404).setBody("no route: ${request.method} $path")
        }
    }

    private fun itemFromPath(path: String): String {
        // /api/me/item/<itemId>/bookmark[/…]
        val parts = path.split('/')
        // ["", "api", "me", "item", "<id>", "bookmark", …]
        return parts[4]
    }

    private fun timeFromPath(path: String): Int {
        // /api/me/item/<itemId>/bookmark/<time>
        val parts = path.split('/')
        return parts[6].toInt()
    }

    private fun handleMe(): MockResponse {
        // AbsApiClient.listBookmarks decodes `/api/me` as `{bookmarks: [...]}` at the top level
        // (see AbsMeBookmarksResponse). Real ABS actually returns a much larger user object;
        // ignoreUnknownKeys means we only need the `bookmarks` field to satisfy the client.
        val body = buildJsonObject {
            put("bookmarks", buildJsonArray {
                for (bm in state) {
                    add(buildJsonObject {
                        put("libraryItemId", bm.libraryItemId)
                        put("title", bm.title)
                        put("time", bm.timeSec)
                        put("createdAt", bm.createdAt)
                    })
                }
            })
        }.toString()
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun handleCreate(itemId: String, request: RecordedRequest): MockResponse {
        val (timeSec, title) = parseTimeAndTitle(request.body.readUtf8()) ?: return badRequest()
        val idx = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        val bm = Bookmark(itemId, timeSec, title, createdAt = 0L)
        if (idx >= 0) state[idx] = bm else state.add(bm)
        return okBookmark(bm)
    }

    private fun handleUpdate(itemId: String, request: RecordedRequest): MockResponse {
        val (timeSec, title) = parseTimeAndTitle(request.body.readUtf8()) ?: return badRequest()
        val idx = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        if (idx < 0) return MockResponse().setResponseCode(404).setBody("not found")
        val bm = state[idx].copy(title = title)
        state[idx] = bm
        return okBookmark(bm)
    }

    private fun handleDelete(itemId: String, timeSec: Int): MockResponse {
        val idx = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        if (idx < 0) {
            // Real ABS returns HTTP 200 "OK" idempotently; this fake models both behaviours so
            // the port-contract "MUST NOT throw on 404" is exercised.
            return if (deleteReturns404OnMiss) {
                MockResponse().setResponseCode(404).setBody("not found")
            } else {
                MockResponse().setResponseCode(200).setBody("OK")
            }
        }
        state.removeAt(idx)
        return MockResponse().setResponseCode(200).setBody("OK")
    }

    private fun badRequest() = MockResponse().setResponseCode(400).setBody("bad request")

    private fun okBookmark(bm: Bookmark): MockResponse {
        val body = buildJsonObject {
            put("libraryItemId", bm.libraryItemId)
            put("title", bm.title)
            put("time", bm.timeSec)
            put("createdAt", bm.createdAt)
        }.toString()
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun parseTimeAndTitle(body: String): Pair<Int, String>? = try {
        val obj = Json.parseToJsonElement(body).jsonObject
        val time = obj["time"]?.jsonPrimitive?.intOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        time to title
    } catch (_: Exception) {
        null
    }
}
