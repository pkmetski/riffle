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
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.logging.RecordingLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToReadRepositoryTest {

    private fun makeRepo(catalog: Catalog?, localStore: FakeLocalToReadStore = FakeLocalToReadStore()) =
        ToReadRepositoryImpl(FakeRegistry(catalog), localStore, RecordingLogger())

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

    // ── refreshForSource ──────────────────────────────────────────────────────

    @Test
    fun `refreshForSource returns false only when server is genuinely offline`() = runTest {
        val cap = FakeCatalog(
            findError = CatalogException.Offline(RuntimeException("connection refused")),
        )
        val repo = makeRepo(cap)
        assertFalse(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun `refreshForSource returns true when server returns a non-network error`() = runTest {
        val cap = FakeCatalog(findError = CatalogException.Auth())
        val repo = makeRepo(cap)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun `refreshForSource populates cache on success`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        val repo = makeRepo(cap)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
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
    fun `addToToRead creates playlist seeded with the item when cache is empty + no playlist on source`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(listOf("lib-1" to "To Read"), cap.createCalls)
        assertEquals(listOf<String?>("item-1"), cap.createSeeds)
        assertEquals(emptyList<Pair<String, String>>(), cap.addCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

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
    fun `addToToRead falls back to create when addItemToPlaylist fails on a stale playlistId`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))), addFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"), "recovery create should heal the tap")
        assertEquals(listOf("lib-1" to "To Read"), cap.createCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead falls back to local store when both add and recovery-create fail`() = runTest {
        val cap = FakeCatalog(
            mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))),
            addFails = true,
            createFails = true,
        )
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead falls back to local store when create fails`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()), createFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
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
    fun `removeFromToRead keeps optimistic remove when DELETE fails`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))), removeFails = true)
        val repo = makeRepo(cap)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead cleans local store even when item is absent from server cache`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to emptyList()))
        val localStore = FakeLocalToReadStore()
        localStore.add("lib-1", "item-local")
        val repoWithLocal = makeRepo(cap, localStore)
        repoWithLocal.refresh("lib-1")
        assertEquals(setOf("item-local"), repoWithLocal.observeToReadItemIds("lib-1").first())
        assertTrue(repoWithLocal.removeFromToRead("item-local", "lib-1"))
        assertEquals(emptySet<String>(), repoWithLocal.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead cleans local store on server success when item was in both cache and local store`() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))))
        val localStore = FakeLocalToReadStore()
        localStore.add("lib-1", "item-1")
        val repo = makeRepo(cap, localStore)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
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
        val findError: Throwable? = null,
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
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)

        override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> {
            if (listFails) throw RuntimeException("boom")
            return playlistsByLibrary[rootId].orEmpty()
        }

        override suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? {
            findError?.let { throw it }
            if (listFails) throw RuntimeException("boom")
            return playlistsByLibrary[rootId].orEmpty().firstOrNull { it.name == name }
        }

        override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist {
            if (createFails) throw RuntimeException("boom")
            createCalls += rootId to name
            createSeeds += initialItemId
            return CatalogPlaylist(
                id = "pl-new",
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

    private class FakeLocalToReadStore : LocalToReadStore {
        private val map = mutableMapOf<String, Set<String>>()
        private val flow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
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
        override suspend fun lastUpdateMs(libraryId: String): Long = 0L
        override suspend fun setAll(libraryId: String, itemIds: Set<String>, lastUpdateMs: Long) {
            map[libraryId] = itemIds
            flow.value = map.toMap()
        }
    }
}
