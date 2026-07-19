package com.riffle.core.data.absbookmark

import com.riffle.core.data.AnnotationFilenames
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.NetworkResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end integration tests: real [AbsApiClient] against a real ABS server. Auto-skipped when
 * the server is unreachable (CI, offline dev boxes) so the JVM test suite stays green.
 *
 * **How to run.** Point at the test ABS via env vars, or accept the defaults which match the
 * user's dev setup:
 *
 * ```
 * RIFFLE_ABS_BASE_URL=http://media-server:13378 \
 * RIFFLE_ABS_USER=test RIFFLE_ABS_PASS=test \
 *   ./gradlew :core:data:testDebugUnitTest \
 *   --tests com.riffle.core.data.absbookmark.AbsBookmarkEndToEndTest
 * ```
 *
 * Each test operates in the Riffle-reserved negative-`time` range with a per-test synthetic device
 * UUID prefix and always cleans up via `forgetNamespace` in a `finally` block, so parallel test
 * runs or leftover state from a previous crashed run don't linger. Foreign bookmarks (yaabsa's
 * `time = -1` blobs; real audio bookmarks at `time ≥ 0`) are asserted-untouched, giving a live
 * regression test that the collision-avoidance layer holds against the actual server.
 */
class AbsBookmarkEndToEndTest {

    private val baseUrl = System.getenv("RIFFLE_ABS_BASE_URL") ?: "http://media-server:13378"
    private val username = System.getenv("RIFFLE_ABS_USER") ?: "test"
    private val password = System.getenv("RIFFLE_ABS_PASS") ?: "test"

    private val okHttp = OkHttpClient()
    private val abs = AbsApiClient(okHttp, DefaultDispatcherProvider)

    private fun assumeAbsReachable() {
        val reachable = try {
            val u = URL("$baseUrl/status")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
        assumeTrue("ABS at $baseUrl not reachable — skipping end-to-end tests", reachable)
    }

    private suspend fun buildTarget(): E2ETargetContext {
        val login = when (val r = abs.login(baseUrl, username, password, insecureAllowed = false)) {
            is NetworkResult.Success -> r.value
            else -> error("ABS login failed: $r")
        }
        val items = when (val r = abs.listBookmarks(baseUrl, login.token, insecureAllowed = false)) {
            is NetworkResult.Success -> r.value
            else -> error("listBookmarks failed: $r")
        }
        // Pick an item that has *any* real signal on the server. Fall back to a hard-coded id that
        // the dev-mode ABS is known to carry so the test still runs on a fresh account.
        val itemId = items.firstOrNull()?.libraryItemId ?: FALLBACK_ITEM_ID
        val ns = "abs_${login.userId}"
        val target = AbsBookmarkAnnotationSyncTarget(
            baseUrl = baseUrl,
            token = login.token,
            insecureAllowed = false,
            accountNamespace = ns,
            api = abs,
        )
        return E2ETargetContext(target, ns, itemId, login.token)
    }

    @Test
    fun `e2e — write read list enumerate delete round-trip`() {
        assumeAbsReachable()
        runBlocking {
            val ctx = buildTarget()
            val target = ctx.target
            val ns = ctx.namespace
            val itemId = ctx.itemId
            val deviceA = "e2e-devA-${System.nanoTime()}"
            val deviceB = "e2e-devB-${System.nanoTime()}"

            try {
                target.forgetNamespace(ns)

                val payloadA = """[{"id":"urn:e2e:A","type":"Annotation","note":"hello A"}]"""
                target.write(ns, itemId, AnnotationFilenames.forDevice(deviceA), payloadA)
                assertEquals(payloadA, target.read(ns, itemId, AnnotationFilenames.forDevice(deviceA)))

                val payloadB = """[{"id":"urn:e2e:B","type":"Annotation","note":"hello B"}]"""
                target.write(ns, itemId, AnnotationFilenames.forDevice(deviceB), payloadB)

                val listed = target.list(ns, itemId).toSet()
                assertEquals(
                    setOf(
                        AnnotationFilenames.forDevice(deviceA),
                        AnnotationFilenames.forDevice(deviceB),
                    ),
                    listed,
                )

                val devices = target.enumerateDevices(ns).devices.map { it.deviceId }.toSet()
                assertTrue("device A missing: $devices", deviceA in devices)
                assertTrue("device B missing: $devices", deviceB in devices)

                target.delete(ns, itemId, AnnotationFilenames.forDevice(deviceA))
                assertNull(target.read(ns, itemId, AnnotationFilenames.forDevice(deviceA)))
                assertEquals(payloadB, target.read(ns, itemId, AnnotationFilenames.forDevice(deviceB)))
            } finally {
                target.forgetNamespace(ns)
            }
        }
    }

    @Test
    fun `e2e — growing then shrinking payload preserves correctness and GCs trailing chunks`() {
        assumeAbsReachable()
        runBlocking {
            val ctx = buildTarget()
            val target = ctx.target
            val ns = ctx.namespace
            val itemId = ctx.itemId
            val deviceA = "e2e-grow-${System.nanoTime()}"

            try {
                target.forgetNamespace(ns)

                val big = highEntropyPayload(400_000)
                target.write(ns, itemId, AnnotationFilenames.forDevice(deviceA), big)
                assertEquals(big, target.read(ns, itemId, AnnotationFilenames.forDevice(deviceA)))

                val small = """[{"id":"urn:e2e:small"}]"""
                target.write(ns, itemId, AnnotationFilenames.forDevice(deviceA), small)
                assertEquals(small, target.read(ns, itemId, AnnotationFilenames.forDevice(deviceA)))

                // Chunk count should now be 2 (manifest + 1 payload), all others GC'd.
                val ours = countReservedRangeBookmarksFor(ctx, itemId, AbsBookmarkChunkCodec.deviceShort(deviceA))
                assertEquals("expected only manifest + 1 chunk after shrink, got $ours", 2, ours)
            } finally {
                target.forgetNamespace(ns)
            }
        }
    }

    @Test
    fun `e2e — forgetNamespace leaves foreign bookmarks untouched`() {
        assumeAbsReachable()
        runBlocking {
            val ctx = buildTarget()
            val target = ctx.target
            val ns = ctx.namespace
            val itemId = ctx.itemId
            val deviceA = "e2e-foreign-${System.nanoTime()}"

            // Precondition: capture the current foreign (non-Riffle) bookmark set so we can
            // assert byte-exact preservation. Any real audio bookmarks the user might have keep
            // out of our reserved range.
            val foreignBefore = allBookmarksForItem(ctx, itemId)
                .filter { AbsBookmarkChunkCodec.parseTitle(it.title) == null }
                .map { Triple(it.timeSec, it.title, it.libraryItemId) }
                .toSet()

            try {
                target.write(ns, itemId, AnnotationFilenames.forDevice(deviceA), """[{"id":"urn:e2e:x"}]""")
                assertNotNull(target.read(ns, itemId, AnnotationFilenames.forDevice(deviceA)))
                target.forgetNamespace(ns)

                val foreignAfter = allBookmarksForItem(ctx, itemId)
                    .filter { AbsBookmarkChunkCodec.parseTitle(it.title) == null }
                    .map { Triple(it.timeSec, it.title, it.libraryItemId) }
                    .toSet()
                assertEquals("foreign bookmarks must be identical before/after", foreignBefore, foreignAfter)

                // Our reserved range for this item must be empty now.
                val ours = countReservedRangeBookmarksFor(ctx, itemId, AbsBookmarkChunkCodec.deviceShort(deviceA))
                assertEquals(0, ours)
            } finally {
                target.forgetNamespace(ns)
            }
        }
    }

    private data class E2ETargetContext(
        val target: AbsBookmarkAnnotationSyncTarget,
        val namespace: String,
        val itemId: String,
        val token: String,
    )

    private suspend fun allBookmarksForItem(ctx: E2ETargetContext, itemId: String) =
        (abs.listBookmarks(baseUrl, ctx.token, insecureAllowed = false) as NetworkResult.Success).value
            .filter { it.libraryItemId == itemId }

    private suspend fun countReservedRangeBookmarksFor(
        ctx: E2ETargetContext,
        itemId: String,
        ownerDeviceShort: String,
    ): Int = allBookmarksForItem(ctx, itemId).count { bm ->
        val parsed = AbsBookmarkChunkCodec.parseTitle(bm.title) ?: return@count false
        parsed.deviceShort == ownerDeviceShort
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

    companion object {
        /**
         * Fallback library item id — the developer's test ABS has this item and it doesn't hurt
         * to write bookmarks to it. Overridable by env var `RIFFLE_ABS_ITEM_ID`.
         */
        private val FALLBACK_ITEM_ID: String =
            System.getenv("RIFFLE_ABS_ITEM_ID") ?: "50b4ee9d-1eef-4534-87d1-4339a667e8e2"
    }
}
