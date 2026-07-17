package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.RecordingLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaylistsRepositoryTest {

    private fun makeRepo(catalog: Catalog?) =
        PlaylistsRepositoryImpl(FakeRegistry(catalog), RecordingLogger())

    // ── the "To Read" invariant ───────────────────────────────────────────────

    /**
     * Regression test for the reserved-name filter. If someone removes the filter in
     * [PlaylistsRepositoryImpl.observePlaylists] or renames [TO_READ_PLAYLIST_NAME], this assertion
     * flips red — which is the point. "To Read" MUST NOT appear in the user-facing Playlists
     * surface; it belongs to [ToReadRepository]'s dedicated tab and affordance.
     */
    @Test
    fun `observePlaylists hides the reserved To Read playlist`() = runTest {
        val cap = FakeCatalog(
            mapOf(
                "lib-1" to listOf(
                    playlist("pl-tr", TO_READ_PLAYLIST_NAME, listOf("i-1")),
                    playlist("pl-a", "Favourites", listOf("i-2")),
                    playlist("pl-b", "Yearly reread", listOf("i-3")),
                ),
            ),
        )
        val repo = makeRepo(cap)
        assertTrue(repo.refresh("lib-1"))
        val names = repo.observePlaylists("lib-1").first().map { it.name }
        assertEquals(listOf("Favourites", "Yearly reread"), names)
    }

    /**
     * "To Listen" is the audiobook wishlist equivalent surfaced by ABS — the general Playlists
     * surface must hide it for the same reason it hides "To Read". Regression test: if the
     * reserved set drops "To Listen" this flips red.
     */
    @Test
    fun `observePlaylists hides the reserved To Listen playlist`() = runTest {
        val cap = FakeCatalog(
            mapOf(
                "lib-1" to listOf(
                    playlist("pl-tl", "To Listen", listOf("i-1")),
                    playlist("pl-a", "Favourites", listOf("i-2")),
                ),
            ),
        )
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertEquals(listOf("Favourites"), repo.observePlaylists("lib-1").first().map { it.name })
    }

    @Test
    fun `createPlaylist rejects the reserved To Listen name`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        try {
            repo.createPlaylist("lib-1", "To Listen")
            fail("expected ReservedPlaylistNameException")
        } catch (_: ReservedPlaylistNameException) {
        }
        assertTrue(cap.createCalls.isEmpty())
    }

    @Test
    fun `observePlaylists reserved-name filter is case- and whitespace-insensitive`() = runTest {
        val cap = FakeCatalog(
            mapOf(
                "lib-1" to listOf(
                    playlist("pl-1", "to read", listOf("i-1")),
                    playlist("pl-2", "  To Read  ", listOf("i-2")),
                    playlist("pl-3", "Favourites", listOf("i-3")),
                ),
            ),
        )
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertEquals(listOf("Favourites"), repo.observePlaylists("lib-1").first().map { it.name })
    }

    @Test
    fun `createPlaylist rejects the reserved name without calling the source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        try {
            repo.createPlaylist("lib-1", TO_READ_PLAYLIST_NAME)
            fail("expected ReservedPlaylistNameException")
        } catch (_: ReservedPlaylistNameException) {
            // expected
        }
        assertTrue("must not hit the source", cap.createCalls.isEmpty())
    }

    @Test
    fun `createPlaylist rejects reserved name case- and whitespace-insensitively`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        for (candidate in listOf("to read", "TO READ", "  To Read  ")) {
            try {
                repo.createPlaylist("lib-1", candidate)
                fail("expected ReservedPlaylistNameException for '$candidate'")
            } catch (_: ReservedPlaylistNameException) {
            }
        }
        assertTrue(cap.createCalls.isEmpty())
    }

    // ── refresh + cache ───────────────────────────────────────────────────────

    @Test
    fun `refresh populates the per-root cache`() = runTest {
        val cap = FakeCatalog(
            mapOf(
                "lib-1" to listOf(playlist("pl-a", "A", listOf("i-1"))),
                "lib-2" to listOf(playlist("pl-b", "B", listOf("i-2"))),
            ),
        )
        val repo = makeRepo(cap)
        assertTrue(repo.refresh("lib-1"))
        assertTrue(repo.refresh("lib-2"))
        assertEquals(listOf("A"), repo.observePlaylists("lib-1").first().map { it.name })
        assertEquals(listOf("B"), repo.observePlaylists("lib-2").first().map { it.name })
    }

    @Test
    fun `refresh returns false on source failure and leaves cache untouched`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()), listFails = true)
        val repo = makeRepo(cap)
        assertFalse(repo.refresh("lib-1"))
        assertEquals(emptyList<CatalogPlaylist>(), repo.observePlaylists("lib-1").first())
    }

    @Test
    fun `refresh treats missing PlaylistsCapability as empty`() = runTest {
        val repo = makeRepo(catalog = null)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(emptyList<CatalogPlaylist>(), repo.observePlaylists("lib-1").first())
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `createPlaylist adds to the cache and returns the created playlist`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        val created = repo.createPlaylist("lib-1", "Favourites", initialItemId = "i-1")
        assertEquals("Favourites", created.name)
        assertEquals(listOf("i-1"), created.itemIds)
        assertEquals(listOf("Favourites"), repo.observePlaylists("lib-1").first().map { it.name })
        assertEquals(listOf("lib-1" to "Favourites"), cap.createCalls)
        assertEquals(listOf<String?>("i-1"), cap.createSeeds)
    }

    @Test
    fun `createPlaylist trims the name before sending`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        repo.createPlaylist("lib-1", "  My List  ")
        assertEquals(listOf("lib-1" to "My List"), cap.createCalls)
    }

    // ── add / remove ──────────────────────────────────────────────────────────

    @Test
    fun `addItemToPlaylist optimistically updates cache then hits source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-a", "Favs", emptyList()))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addItemToPlaylist("lib-1", "pl-a", "i-1"))
        assertEquals(listOf("pl-a" to "i-1"), cap.addCalls)
        assertEquals(listOf("i-1"), repo.observePlaylists("lib-1").first().first().itemIds)
    }

    @Test
    fun `addItemToPlaylist reverts cache when source fails`() = runTest {
        val cap = FakeCatalog(
            mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-0")))),
            addFails = true,
        )
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertFalse(repo.addItemToPlaylist("lib-1", "pl-a", "i-1"))
        assertEquals(listOf("i-0"), repo.observePlaylists("lib-1").first().first().itemIds)
    }

    @Test
    fun `addItemToPlaylist is idempotent when item already present`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-1")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addItemToPlaylist("lib-1", "pl-a", "i-1"))
        assertTrue("no source call for a no-op", cap.addCalls.isEmpty())
    }

    @Test
    fun `removeItemFromPlaylist removes and hits source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-1", "i-2")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeItemFromPlaylist("lib-1", "pl-a", "i-1"))
        assertEquals(listOf("pl-a" to "i-1"), cap.removeCalls)
        assertEquals(listOf("i-2"), repo.observePlaylists("lib-1").first().first().itemIds)
    }

    /**
     * When the last item is removed, ABS auto-deletes the playlist server-side. The repository
     * mirrors that by dropping the playlist from the cache so the tab hides immediately without
     * waiting for the next refresh — pinning that behaviour here.
     */
    @Test
    fun `removeItemFromPlaylist drops empty playlist from cache to mirror server auto-delete`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-1")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeItemFromPlaylist("lib-1", "pl-a", "i-1"))
        assertEquals(emptyList<CatalogPlaylist>(), repo.observePlaylists("lib-1").first())
    }

    @Test
    fun `removeItemFromPlaylist reverts cache when source fails`() = runTest {
        val cap = FakeCatalog(
            mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-1", "i-2")))),
            removeFails = true,
        )
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertFalse(repo.removeItemFromPlaylist("lib-1", "pl-a", "i-1"))
        assertEquals(listOf("i-1", "i-2"), repo.observePlaylists("lib-1").first().first().itemIds)
    }

    // ── getPlaylist ───────────────────────────────────────────────────────────

    @Test
    fun `getPlaylist returns the full playlist including To Read (repository is not the filter for detail lookups)`() = runTest {
        // A caller with the id already in hand (e.g. from a nav argument) may be inside a screen
        // that shouldn't exist for To Read, but the repository itself only filters the LIST — the
        // per-id lookup is transparent so a debugging session can still resolve it if needed.
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-a", "Favs", listOf("i-1")))))
        val repo = makeRepo(cap)
        val loaded = repo.getPlaylist("lib-1", "pl-a")
        assertNotNull(loaded)
        assertEquals("Favs", loaded!!.name)
        assertEquals(listOf("i-1"), loaded.itemIds)
    }

    @Test
    fun `getPlaylist returns null when the playlist is missing on the source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        assertNull(makeRepo(cap).getPlaylist("lib-1", "pl-missing"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun playlist(id: String, name: String, itemIds: List<String>) = CatalogPlaylist(
        id = id, rootId = "lib-1", name = name, bookCount = itemIds.size, itemIds = itemIds,
    )

    private class FakeRegistry(private val catalog: Catalog?) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private class FakeCatalog(
        val playlistsByLibrary: Map<String, List<CatalogPlaylist>> = emptyMap(),
        val listFails: Boolean = false,
        val createFails: Boolean = false,
        val addFails: Boolean = false,
        val removeFails: Boolean = false,
    ) : Catalog, PlaylistsCapability {
        val createCalls = mutableListOf<Pair<String, String>>()
        val createSeeds = mutableListOf<String?>()
        val addCalls = mutableListOf<Pair<String, String>>()
        val removeCalls = mutableListOf<Pair<String, String>>()

        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) =
            emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) =
            emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
            throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream =
            throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)

        override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> {
            if (listFails) throw RuntimeException("boom")
            return playlistsByLibrary[rootId].orEmpty()
        }

        override suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? =
            playlistsByLibrary[rootId].orEmpty().firstOrNull { it.name == name }

        override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist {
            if (createFails) throw RuntimeException("boom")
            createCalls += rootId to name
            createSeeds += initialItemId
            return CatalogPlaylist(
                id = "pl-new-${createCalls.size}",
                rootId = rootId,
                name = name,
                bookCount = if (initialItemId != null) 1 else 0,
                itemIds = if (initialItemId != null) listOf(initialItemId) else emptyList(),
            )
        }

        override suspend fun addItemToPlaylist(playlistId: String, itemId: String) {
            if (addFails) throw RuntimeException("boom")
            addCalls += playlistId to itemId
        }

        override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) {
            if (removeFails) throw RuntimeException("boom")
            removeCalls += playlistId to itemId
        }
    }
}
