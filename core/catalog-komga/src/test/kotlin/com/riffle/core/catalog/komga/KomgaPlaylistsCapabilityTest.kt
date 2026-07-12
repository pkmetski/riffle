package com.riffle.core.catalog.komga

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
import org.junit.Before
import org.junit.Test

/**
 * Verifies [KomgaCatalog]'s [com.riffle.core.catalog.PlaylistsCapability] implementation against
 * Komga's `/api/v1/readlists*` endpoints. Each test's assertion would flip red if the mapping,
 * request shape, or empty-list DELETE behaviour were reverted.
 */
class KomgaPlaylistsCapabilityTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: KomgaCatalog

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val ok = OkHttpClient()
        val header = buildBasicAuthHeader("user", "pass")
        val config = KomgaCatalogConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            basicAuthHeader = header,
            insecureAllowed = true,
        )
        catalog = KomgaCatalog(config, KomgaHttpClient(ok, header), ok)
    }

    @After fun tearDown() { server.shutdown() }

    /** Every capability path routes through `firstOwnedReadListNamed` → `currentUserId`, which
     * hits `GET /api/v1/users/me` once per catalog instance. Tests that don't care about
     * ownership call [enqueueMe] at the top of their body so the /me GET is answered before the
     * readlist workflow runs. Cached across calls within a single catalog, so a test only needs
     * to enqueue /me once. */
    private fun enqueueMe(id: String = "USER_ME") {
        server.enqueue(MockResponse().setBody("""{"id":"$id"}"""))
    }

    // Regression: Komga readlists span libraries. If findPlaylist returned the raw bookIds from
    // the readlist DTO, the "To Read" tab in library L1 would show books living in L2, and vice
    // versa. Filter must go through /readlists/{id}/books?library_id=L1.
    @Test fun `findPlaylist returns only books in the requested library`() = runTest {
        enqueueMe()
        // 1st call: GET /api/v1/readlists?size=1000&page=0
        server.enqueue(readListPage(
            """{"id":"RL1","name":"To Read","bookIds":["B_L1_1","B_L2_1","B_L1_2","B_L2_2"],"ownerId":"USER_ME"}"""
        ))
        // 2nd call: GET /api/v1/readlists/RL1/books?library_id=L1&size=1000&page=0
        server.enqueue(bookPage(
            """{"id":"B_L1_1","libraryId":"L1","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"L1 book 1","authors":[]}}""",
            """{"id":"B_L1_2","libraryId":"L1","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"L1 book 2","authors":[]}}""",
        ))

        val playlist = catalog.findPlaylist(rootId = "L1", name = "To Read")

        assertNotNull(playlist)
        assertEquals("RL1", playlist!!.id)
        assertEquals("To Read", playlist.name)
        assertEquals(listOf("B_L1_1", "B_L1_2"), playlist.itemIds)
        assertEquals("L1", playlist.rootId)

        server.takeRequest() // /users/me
        val listReq = server.takeRequest()
        assertTrue(listReq.path!!.startsWith("/api/v1/readlists?"))
        val booksReq = server.takeRequest()
        assertEquals("/api/v1/readlists/RL1/books?library_id=L1&size=1000&page=0", booksReq.path)
    }

    @Test fun `findPlaylist returns null when named readlist is absent`() = runTest {
        enqueueMe()
        server.enqueue(readListPage("""{"id":"RL1","name":"Favourites","bookIds":[],"ownerId":"USER_ME"}"""))

        val playlist = catalog.findPlaylist(rootId = "L1", name = "To Read")

        assertNull(playlist)
    }

    // Regression: a "To Read" readlist created by another user (or via Komga's web UI while
    // authenticated as a different user) is visible to us (GET succeeds) but rejects our PATCH
    // with 403 because Komga scopes readlist modification to owner + admins. Reusing that id
    // means every add-to-to-read call silently fails. The fix is to skip foreign-owned matches
    // in findPlaylist so createPlaylist falls through to POSTing a fresh, owned readlist. The
    // regression this test locks in showed up as `Komga HTTP 403 PATCH /api/v1/readlists/{id}`
    // in RIFFLE_TOREAD logs against the user's server.
    @Test fun `findPlaylist skips readlist owned by another user`() = runTest {
        // 1st call inside firstOwnedReadListNamed: GET /api/v1/users/me
        server.enqueue(MockResponse().setBody("""{"id":"USER_ME"}"""))
        // 2nd call: GET /api/v1/readlists — the "To Read" list is owned by someone else.
        server.enqueue(readListPage(
            """{"id":"RL_STRANGER","name":"To Read","bookIds":["B1"],"ownerId":"USER_OTHER"}"""
        ))

        val playlist = catalog.findPlaylist(rootId = "L1", name = "To Read")

        assertNull("must not hand back a readlist that will 403 on the next PATCH", playlist)
    }

    @Test fun `findPlaylist matches readlist owned by current user`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"USER_ME"}"""))
        server.enqueue(readListPage(
            """{"id":"RL_MINE","name":"To Read","bookIds":["B1"],"ownerId":"USER_ME"}"""
        ))
        server.enqueue(bookPage(
            """{"id":"B1","libraryId":"L1","media":{"mediaType":"application/epub+zip"},"metadata":{"title":"One","authors":[]}}"""
        ))

        val playlist = catalog.findPlaylist(rootId = "L1", name = "To Read")

        assertNotNull(playlist)
        assertEquals("RL_MINE", playlist!!.id)
    }

    // Older Komga (< 1.19) omits ownerId — accept those matches so we don't regress users on
    // older servers who don't have per-user ownership at all.
    @Test fun `findPlaylist matches readlist with no ownerId (older Komga)`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"USER_ME"}"""))
        server.enqueue(readListPage(
            """{"id":"RL_OLD","name":"To Read","bookIds":[]}"""
        ))
        server.enqueue(bookPage())

        val playlist = catalog.findPlaylist(rootId = "L1", name = "To Read")

        assertNotNull(playlist)
        assertEquals("RL_OLD", playlist!!.id)
    }

    // When the only "To Read" on the server is foreign-owned, createPlaylist must POST a fresh
    // one owned by the current user rather than returning the foreign id.
    @Test fun `createPlaylist ignores foreign-owned readlist and POSTs a new one`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"USER_ME"}"""))
        server.enqueue(readListPage(
            """{"id":"RL_STRANGER","name":"To Read","bookIds":["B_OLD"],"ownerId":"USER_OTHER"}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"id":"RL_MINE","name":"To Read","bookIds":["B_NEW"],"ownerId":"USER_ME"}"""
        ))

        val created = catalog.createPlaylist(rootId = "L1", name = "To Read", initialItemId = "B_NEW")

        assertEquals("RL_MINE", created.id)
        server.takeRequest() // GET users/me
        server.takeRequest() // GET readlists
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertTrue(postReq.body.readUtf8().contains("\"bookIds\":[\"B_NEW\"]"))
    }

    // Regression: createPlaylist must be idempotent by name so two libraries that share the same
    // Komga server don't each POST a duplicate "To Read" readlist on first-add. Simulate a
    // pre-existing readlist owned by the current user and verify the code returns it instead of
    // POSTing.
    @Test fun `createPlaylist reuses existing readlist by name when we own it`() = runTest {
        enqueueMe()
        server.enqueue(readListPage("""{"id":"RL42","name":"To Read","bookIds":[],"ownerId":"USER_ME"}"""))

        val created = catalog.createPlaylist(rootId = "L1", name = "To Read")

        assertEquals("RL42", created.id)
        assertEquals("To Read", created.name)
        server.takeRequest() // /users/me
        val listReq = server.takeRequest()
        assertTrue(listReq.path!!.startsWith("/api/v1/readlists?"))
        assertEquals("GET", listReq.method)
        // No POST — only /users/me + /readlists.
        assertEquals(2, server.requestCount)
    }

    // Regression: Komga's POST /api/v1/readlists REQUIRES a non-empty bookIds; creating an empty
    // readlist returns 400. The interface's `initialItemId` is the seed mechanism callers use to
    // sidestep that mandatory field. The POST body must include the seeded bookId — an earlier
    // (broken) version encoded bookIds as [] and every first-time "add to To Read" tap failed
    // with a 400 that was swallowed by ToReadRepositoryImpl's runCatching, giving the user a
    // silent no-op. This test locks in the seeded-POST behaviour that fixes it.
    @Test fun `createPlaylist with initialItemId POSTs seeded readlist when none matches`() = runTest {
        enqueueMe()
        server.enqueue(readListPage(/* empty page */))
        server.enqueue(MockResponse().setBody(
            """{"id":"RL_NEW","name":"To Read","bookIds":["B_SEED"],"ownerId":"USER_ME"}"""
        ))

        val created = catalog.createPlaylist(rootId = "L1", name = "To Read", initialItemId = "B_SEED")

        assertEquals("RL_NEW", created.id)
        assertEquals(listOf("B_SEED"), created.itemIds)
        server.takeRequest() // /users/me
        server.takeRequest() // GET readlists
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/api/v1/readlists", postReq.path)
        val body = postReq.body.readUtf8()
        assertTrue("POST body should contain the name", body.contains("\"name\":\"To Read\""))
        assertTrue("POST body must seed bookIds with the initial item", body.contains("\"bookIds\":[\"B_SEED\"]"))
    }

    // When the readlist already exists on the server and the caller supplies an initialItemId
    // (i.e. concurrent library refreshes → both hit createPlaylist with a seed), append the seed
    // to the existing bookIds so the caller's expectation ("this playlist now contains my item")
    // holds. Idempotent when the seed is already present.
    // Regression: an earlier version returned CatalogPlaylist(bookCount=0, itemIds=emptyList())
    // on the reuse branch — a silent contract violation ("returned playlist already contains
    // that item" per PlaylistsCapability.createPlaylist kdoc). Callers relying on the returned
    // itemIds would see always-empty on reuse. Now the return reflects the true post-PATCH
    // state.
    @Test fun `createPlaylist reuse-branch return reflects post-PATCH bookIds`() = runTest {
        enqueueMe()
        server.enqueue(readListPage(
            """{"id":"RL_EXISTING","name":"To Read","bookIds":["B_OLD"],"ownerId":"USER_ME"}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        val created = catalog.createPlaylist(rootId = "L1", name = "To Read", initialItemId = "B_SEED")

        assertEquals("RL_EXISTING", created.id)
        assertEquals(listOf("B_OLD", "B_SEED"), created.itemIds)
        assertEquals(2, created.bookCount)
    }

    // Regression: Komga returns `filtered=true` when the readlist contains books the caller
    // can't see (shared-libraries scope, mid-session access revocation). Our PATCH replaces
    // bookIds wholesale — if we built the PATCH from a filtered readlist, we'd silently DELETE
    // the hidden books from the server-side list. Refuse the mutation instead so the user's tap
    // gets reverted (and logged via RIFFLE_TOREAD) rather than corrupting the shared readlist.
    @Test fun `addItemToPlaylist refuses when server-returned readlist is filtered`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B_VISIBLE"],"filtered":true}"""
        ))

        try {
            catalog.addItemToPlaylist(playlistId = "RL1", itemId = "B_NEW")
            org.junit.Assert.fail("expected refusal — a PATCH with a filtered bookIds list would erase hidden books")
        } catch (e: KomgaHttpException) {
            assertEquals("GUARD", e.method)
            assertTrue(e.responseBody.contains("filtered"))
        }
        // No PATCH sent — the guard fires before the write.
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test fun `removeItemFromPlaylist refuses when server-returned readlist is filtered`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B_VISIBLE","B_TARGET"],"filtered":true}"""
        ))

        try {
            catalog.removeItemFromPlaylist(playlistId = "RL1", itemId = "B_TARGET")
            org.junit.Assert.fail("expected refusal on filtered readlist")
        } catch (e: KomgaHttpException) {
            assertEquals("GUARD", e.method)
        }
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    // Regression: an earlier version silently swallowed /users/me failures with runCatching and
    // returned null. On modern Komga (≥1.19) with non-null ownerId on every readlist, the
    // ownership predicate then filtered out EVERY owned readlist → createPlaylist POSTed a
    // duplicate on every operation while /me was flaky. Now /me failures throw and propagate
    // so the repo layer's runCatching logs via RIFFLE_TOREAD and reverts the optimistic add.
    @Test fun `findPlaylist throws when users me is unreachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream failure"))

        try {
            catalog.findPlaylist(rootId = "L1", name = "To Read")
            org.junit.Assert.fail("expected /users/me failure to surface, not be silently swallowed")
        } catch (e: KomgaHttpException) {
            assertEquals(500, e.code)
            assertTrue(e.url.endsWith("/api/v1/users/me"))
        }
    }

    @Test fun `createPlaylist appends initialItemId to existing owned readlist`() = runTest {
        enqueueMe()
        server.enqueue(readListPage(
            """{"id":"RL_EXISTING","name":"To Read","bookIds":["B_OLD"],"ownerId":"USER_ME"}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        val created = catalog.createPlaylist(rootId = "L1", name = "To Read", initialItemId = "B_SEED")

        assertEquals("RL_EXISTING", created.id)
        server.takeRequest() // /users/me
        server.takeRequest() // GET readlists
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/v1/readlists/RL_EXISTING", patchReq.path)
        assertEquals("""{"bookIds":["B_OLD","B_SEED"]}""", patchReq.body.readUtf8())
    }

    // Regression: Komga PATCH replaces bookIds wholesale. If addItemToPlaylist forgot to include
    // the current bookIds and only PATCHed with the new one, every existing To-Read entry would
    // vanish on the next add. Assertion: PATCH body contains both the old and new ids in order.
    @Test fun `addItemToPlaylist PATCHes with appended bookIds preserving order`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B1","B2"]}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.addItemToPlaylist(playlistId = "RL1", itemId = "B3")

        server.takeRequest() // GET readlist
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/v1/readlists/RL1", patchReq.path)
        val body = patchReq.body.readUtf8()
        assertEquals("""{"bookIds":["B1","B2","B3"]}""", body)
    }

    @Test fun `addItemToPlaylist is idempotent when book already present`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B1","B2","B3"]}"""
        ))

        catalog.addItemToPlaylist(playlistId = "RL1", itemId = "B2")

        // Only GET — no PATCH.
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test fun `removeItemFromPlaylist PATCHes without removed book`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B1","B2","B3"]}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.removeItemFromPlaylist(playlistId = "RL1", itemId = "B2")

        server.takeRequest() // GET
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("""{"bookIds":["B1","B3"]}""", patchReq.body.readUtf8())
    }

    // Regression: Komga does NOT auto-delete empty readlists (ABS does). If Riffle only PATCHed
    // to an empty bookIds array, the "To Read" readlist would linger on the server as an empty
    // shell, and — more painfully — the next add would find it and re-use its id even though the
    // caller-side snapshot already dropped playlistId. This test locks in the DELETE-on-empty
    // sweep that mirrors ABS's server-side behaviour.
    @Test fun `removeItemFromPlaylist DELETEs the readlist when the last book is removed`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":["B1"]}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.removeItemFromPlaylist(playlistId = "RL1", itemId = "B1")

        server.takeRequest() // GET
        val delReq = server.takeRequest()
        assertEquals("DELETE", delReq.method)
        assertEquals("/api/v1/readlists/RL1", delReq.path)
    }

    @Test fun `listPlaylists returns summary only with empty itemIds`() = runTest {
        server.enqueue(readListPage(
            """{"id":"RL1","name":"To Read","bookIds":["B1","B2"]}""",
            """{"id":"RL2","name":"Favourites","bookIds":["B3"]}""",
        ))

        val result = catalog.listPlaylists(rootId = "L1")

        assertEquals(2, result.size)
        assertEquals("RL1", result[0].id)
        assertEquals(emptyList<String>(), result[0].itemIds)
        assertEquals(emptyList<String>(), result[1].itemIds)
        // No per-readlist book fetches — only the single readlists-list request.
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    // Sanity check on the payload sent by Komga's PATCH: must serialize as a plain object with a
    // single `bookIds` array, not wrapped in another key. Locked in because kotlinx-serialization
    // defaults would happily emit unexpected structures if the DTO were changed to have extra
    // optional fields with non-null defaults.
    @Test fun `patch body shape is exactly bookIds`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"RL1","name":"To Read","bookIds":[]}"""
        ))
        server.enqueue(MockResponse().setResponseCode(204))

        catalog.addItemToPlaylist(playlistId = "RL1", itemId = "B1")

        server.takeRequest()
        val patch = server.takeRequest()
        assertEquals("""{"bookIds":["B1"]}""", patch.body.readUtf8())
    }

    private fun readListPage(vararg readListsJson: String): MockResponse {
        val content = readListsJson.joinToString(",")
        return MockResponse().setBody(
            """{"content":[$content],"totalPages":1,"totalElements":${readListsJson.size},"first":true,"last":true,"empty":${readListsJson.isEmpty()}}"""
        )
    }

    private fun bookPage(vararg booksJson: String): MockResponse {
        val content = booksJson.joinToString(",")
        return MockResponse().setBody(
            """{"content":[$content],"totalPages":1,"totalElements":${booksJson.size},"first":true,"last":true,"empty":${booksJson.isEmpty()}}"""
        )
    }

    @Suppress("unused") // reserved for future assertions on request auth headers
    private fun RecordedRequest.hasBasicAuth(): Boolean =
        getHeader("Authorization")?.startsWith("Basic ") == true
}
