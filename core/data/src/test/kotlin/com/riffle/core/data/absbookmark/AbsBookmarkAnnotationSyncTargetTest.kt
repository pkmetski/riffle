package com.riffle.core.data.absbookmark

import com.riffle.core.data.AnnotationFilenames
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "http://abs.local"
private const val TOKEN = "token-123"
private const val NS = "abs_media_server_test"
private const val ITEM = "book-1"

class AbsBookmarkAnnotationSyncTargetTest {

    private val deviceA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val deviceB = "d4a2c5e7-1b7f-4d3e-8c1a-2b6f4d3e5c1a"

    private fun target(api: FakeAbsBookmarkApi) = AbsBookmarkAnnotationSyncTarget(
        baseUrl = BASE,
        token = TOKEN,
        insecureAllowed = false,
        accountNamespace = NS,
        api = api,
        clock = { 1_700_000_000_000L },
    )

    @Test
    fun `write then read round-trips a payload`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        val payload = """[{"id":"urn:a:1","type":"Annotation"}]"""
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), payload)
        val readback = t.read(NS, ITEM, AnnotationFilenames.forDevice(deviceA))
        assertEquals(payload, readback)
    }

    @Test
    fun `write is idempotent for identical content`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        val payload = """[{"id":"urn:a:1"}]"""
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), payload)
        val callsBefore = api.callLog.size
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), payload)
        // Only the extra list() call — no create/update/delete when content is unchanged.
        val delta = api.callLog.drop(callsBefore).filter { it !is FakeAbsBookmarkApi.Call.List }
        assertTrue("expected no mutating calls when content unchanged, got: $delta", delta.isEmpty())
    }

    @Test
    fun `shrinking payload GCs trailing chunks`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        val big = highEntropyPayload(500_000)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), big)
        val chunksBefore = api.bookmarksFor(ITEM).size
        assertTrue("expected multi-chunk shard", chunksBefore >= 3)

        val small = """[{"id":"urn:a:1"}]"""
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), small)
        val after = api.bookmarksFor(ITEM)
        // Manifest + 1 payload chunk = 2 for the small case.
        assertEquals(2, after.size)
        val readback = t.read(NS, ITEM, AnnotationFilenames.forDevice(deviceA))
        assertEquals(small, readback)
    }

    @Test
    fun `list returns filenames keyed by full deviceId recovered from manifest`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceB), """[{"id":"b"}]""")
        val listed = t.list(NS, ITEM).sorted()
        val expected = listOf(
            AnnotationFilenames.forDevice(deviceA),
            AnnotationFilenames.forDevice(deviceB),
        ).sorted()
        assertEquals(expected, listed)
    }

    @Test
    fun `read accepts either full deviceId filename or deviceShort filename`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        val payload = """[{"id":"a"}]"""
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), payload)
        // Full-uuid filename (as returned by list()) round-trips.
        assertEquals(payload, t.read(NS, ITEM, AnnotationFilenames.forDevice(deviceA)))
        // Short-form filename (fallback when manifest is torn) also round-trips.
        assertEquals(payload, t.read(NS, ITEM, AnnotationFilenames.forDevice(AbsBookmarkChunkCodec.deviceShort(deviceA))))
    }

    @Test
    fun `delete removes only the requested device's chunks`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceB), """[{"id":"b"}]""")
        t.delete(NS, ITEM, AnnotationFilenames.forDevice(AbsBookmarkChunkCodec.deviceShort(deviceA)))
        // Device A gone; Device B intact.
        assertNull(t.read(NS, ITEM, AnnotationFilenames.forDevice(AbsBookmarkChunkCodec.deviceShort(deviceA))))
        val bReadback = t.read(NS, ITEM, AnnotationFilenames.forDevice(deviceB))
        assertEquals("""[{"id":"b"}]""", bReadback)
    }

    @Test
    fun `enumerateDevices recovers full deviceId from manifest`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        t.write(NS, "book-2", AnnotationFilenames.forDevice(deviceA), """[{"id":"a2"}]""")
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceB), """[{"id":"b"}]""")
        val listing = t.enumerateDevices(NS)
        assertEquals(2, listing.devices.size)
        val deviceIds = listing.devices.map { it.deviceId }.toSet()
        assertEquals(setOf(deviceA, deviceB), deviceIds)
        val aRow = listing.devices.first { it.deviceId == deviceA }
        assertEquals(setOf(ITEM, "book-2"), aRow.annotationFiles.map { it.itemId }.toSet())
    }

    @Test
    fun `ignores foreign bookmarks — yaabsa and real audio`() = runTest {
        val api = FakeAbsBookmarkApi()
        // Pre-seed noise.
        api.state.add(NetworkAbsBookmark(ITEM, "[{\"cfi\":\"…\",\"type\":\"highlight\"}]", -1, 100L))
        api.state.add(NetworkAbsBookmark(ITEM, "Great quote", 300, 200L))
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")

        val readback = t.read(NS, ITEM, AnnotationFilenames.forDevice(deviceA))
        assertEquals("""[{"id":"a"}]""", readback)
        // Foreign bookmarks still there — we didn't touch them.
        assertTrue(api.state.any { it.timeSec == -1 })
        assertTrue(api.state.any { it.timeSec == 300 })
    }

    @Test
    fun `forgetNamespace deletes only our reserved-range bookmarks`() = runTest {
        val api = FakeAbsBookmarkApi()
        // Foreign yaabsa bookmark stays.
        api.state.add(NetworkAbsBookmark(ITEM, "[{\"cfi\":\"…\"}]", -1, 100L))
        api.state.add(NetworkAbsBookmark(ITEM, "audio note", 500, 200L))
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        t.write(NS, "book-2", AnnotationFilenames.forDevice(deviceB), """[{"id":"b"}]""")

        val deleted = t.forgetNamespace(NS)
        assertTrue("expected at least 4 deletions (2 books × [manifest + 1 chunk]), got $deleted", deleted >= 4)
        // Foreign bookmarks preserved.
        assertTrue(api.state.any { it.timeSec == -1 })
        assertTrue(api.state.any { it.timeSec == 500 })
        // None of our reserved-range bookmarks remain.
        val ownRemaining = api.state.count { AbsBookmarkChunkCodec.parseTimeSlot(it.timeSec) != null }
        assertEquals(0, ownRemaining)
    }

    @Test
    fun `foreign namespace is rejected`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                t.list("some_other_ns", ITEM)
            }
        }
    }

    @Test
    fun `enumerateNamespaces returns zero when empty`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        assertTrue(t.enumerateNamespaces().isEmpty())
    }

    @Test
    fun `enumerateNamespaces returns own account when populated`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        t.write(NS, ITEM, AnnotationFilenames.forDevice(deviceA), """[{"id":"a"}]""")
        val ns = t.enumerateNamespaces()
        assertEquals(1, ns.size)
        assertEquals(NS, ns.first().namespace)
        assertTrue(ns.first().annotationFileCount > 0)
    }

    @Test
    fun `readDeviceMeta returns null and writeDeviceMeta is a no-op — v1 gap`() = runTest {
        val api = FakeAbsBookmarkApi()
        val t = target(api)
        t.writeDeviceMeta(NS, deviceA, "{\"label\":\"Phone A\"}")
        assertNull(t.readDeviceMeta(NS, deviceA))
        assertTrue(api.state.isEmpty())
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
 * Minimal in-memory ABS bookmark API for tests. Behavior matches the real server as observed via
 * empirical probes: `(libraryItemId, timeSec)` is primary key; `createBookmark` at an existing
 * slot updates in place (matches real ABS `createBookmark` model semantics).
 */
private class FakeAbsBookmarkApi : AbsBookmarkApi {
    val state: MutableList<NetworkAbsBookmark> = mutableListOf()
    val callLog: MutableList<Call> = mutableListOf()

    sealed class Call {
        data object List : Call()
        data class Create(val itemId: String, val timeSec: Int) : Call()
        data class Update(val itemId: String, val timeSec: Int) : Call()
        data class Delete(val itemId: String, val timeSec: Int) : Call()
    }

    fun bookmarksFor(itemId: String): List<NetworkAbsBookmark> = state.filter { it.libraryItemId == itemId }

    override suspend fun createBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> {
        callLog.add(Call.Create(itemId, timeSec))
        val existing = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        val bm = NetworkAbsBookmark(itemId, title, timeSec, createdAt = 0L)
        if (existing >= 0) state[existing] = bm else state.add(bm)
        return NetworkResult.Success(bm)
    }

    override suspend fun updateBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        title: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> {
        callLog.add(Call.Update(itemId, timeSec))
        val idx = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        if (idx < 0) return NetworkResult.ServerError(404, "not found")
        val updated = state[idx].copy(title = title)
        state[idx] = updated
        return NetworkResult.Success(updated)
    }

    override suspend fun deleteBookmark(
        baseUrl: String,
        itemId: String,
        timeSec: Int,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> {
        callLog.add(Call.Delete(itemId, timeSec))
        val idx = state.indexOfFirst { it.libraryItemId == itemId && it.timeSec == timeSec }
        if (idx < 0) {
            // ABS returns HTTP 200 "OK" even when deleting a non-existent bookmark (idempotent).
            return NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))
        }
        val removed = state.removeAt(idx)
        return NetworkResult.Success(removed)
    }

    override suspend fun listBookmarks(
        baseUrl: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> {
        callLog.add(Call.List)
        return NetworkResult.Success(state.toList())
    }
}
