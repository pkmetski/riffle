package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogCollection
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToReadRepositoryTest {

    private fun makeRepo(catalog: Catalog?) = ToReadRepositoryImpl(FakeRegistry(catalog), FakeLocalToReadStore())

    // ── refresh + observeToReadItemIds ────────────────────────────────────────

    @Test
    fun `refresh populates cache from source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1", "item-2")))))
        val repo = makeRepo(cap)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(setOf("item-1", "item-2"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh populates empty when no To Read playlist exists`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh returns false on network error and leaves cache untouched`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()), listFails = true)
        val repo = makeRepo(cap)
        assertFalse(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `isInToRead reads from cache after refresh`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.isInToRead("item-1", "lib-1"))
        assertFalse(repo.isInToRead("item-9", "lib-1"))
    }

    @Test
    fun `isInToRead returns false before any refresh`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        assertFalse(makeRepo(cap).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead appends to existing playlist and updates cache optimistically`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertTrue(cap.createCalls.isEmpty())
        assertEquals(listOf("pl-A" to "item-1"), cap.addCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead creates playlist when cache is empty + no playlist on source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(listOf("lib-1" to "To Read"), cap.createCalls)
        assertEquals(listOf("pl-new" to "item-1"), cap.addCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    /**
     * When there is no server-side [PlaylistsCapability], the repository falls back to
     * [LocalToReadStore] — the operation now succeeds and the id is observable. Pre-fallback this
     * test asserted `assertFalse` (the whole point of the fallback change).
     */
    @Test
    fun `addToToRead uses local fallback when no PlaylistsCapability for active source`() = runTest {
        val repo = makeRepo(catalog = null)
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
        assertTrue(repo.isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `removeFromToRead uses local fallback when no PlaylistsCapability for active source`() = runTest {
        val repo = makeRepo(catalog = null)
        repo.addToToRead("item-1", "lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead reverts cache when add fails`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))), addFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead reverts cache when create fails`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()), createFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead calls DELETE and updates cache`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(listOf("pl-A" to "item-1"), cap.removeCalls)
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead clears cached playlistId when last item is removed`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        // Next add must create a new playlist, not POST to the dead pl-A.
        assertTrue(repo.addToToRead("item-2", "lib-1"))
        assertEquals(listOf("lib-1" to "To Read"), cap.createCalls)
    }

    @Test
    fun `removeFromToRead returns true and makes no call when cache empty`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertTrue(cap.removeCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead reverts cache when DELETE fails`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))), removeFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertFalse(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

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
        val addCalls = mutableListOf<Pair<String, String>>()
        val removeCalls = mutableListOf<Pair<String, String>>()

        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)

        override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> {
            if (listFails) throw RuntimeException("boom")
            return playlistsByLibrary[rootId].orEmpty()
        }

        override suspend fun createPlaylist(rootId: String, name: String): CatalogPlaylist {
            if (createFails) throw RuntimeException("boom")
            createCalls += rootId to name
            return CatalogPlaylist(id = "pl-new", rootId = rootId, name = name, bookCount = 0)
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

    /**
     * Trivial in-memory fake for the local fallback. These tests exercise the ABS path (Catalog is
     * PlaylistsCapability), so this fake never gets touched — it exists only to satisfy the
     * [ToReadRepositoryImpl] constructor.
     */
    private class FakeLocalToReadStore : LocalToReadStore {
        private val map = mutableMapOf<String, Set<String>>()
        private val flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        override fun observeItemIds(libraryId: String) =
            flow.map { it[libraryId].orEmpty() }
        override suspend fun isInToRead(libraryId: String, libraryItemId: String) =
            map[libraryId]?.contains(libraryItemId) == true
        override suspend fun add(libraryId: String, libraryItemId: String) {
            map[libraryId] = map[libraryId].orEmpty() + libraryItemId
            flow.value = map.toMap()
        }
        override suspend fun remove(libraryId: String, libraryItemId: String) {
            map[libraryId] = map[libraryId].orEmpty() - libraryItemId
            flow.value = map.toMap()
        }
    }
}
