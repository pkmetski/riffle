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
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.abs.CatalogException
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Platform-neutral counterpart to the androidHostTest [ToReadRepositoryTest].
 *
 * Pins the key behavioural contracts of [ToReadRepositoryImpl] using commonMain abstractions
 * ([CatalogRegistry], [PlaylistsCapability], [CatalogException]) so the same assertions run
 * against the iOS execution path via iosSimulatorArm64Test.
 *
 * Scenarios derived from [IosToReadRepositoryImplTest] (deleted when [IosToReadRepositoryImpl]
 * was removed) are covered here using the CatalogRegistry layer that [ToReadRepositoryImpl] uses.
 */
class ToReadRepositoryImplTest {

    private fun makeRepo(
        catalog: Catalog?,
        localStore: FakeLocalToReadStore = FakeLocalToReadStore(),
    ) = ToReadRepositoryImpl(FakeRegistry(catalog), localStore, RecordingLogger())

    // ── refreshForSource return-value semantics ───────────────────────────────

    @Test
    fun refreshForSourceReturnsTrueOnSuccess() = runTest {
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-1", "To Read", listOf("item-1")))))
        val repo = makeRepo(cap)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun refreshForSourceReturnsFalseWhenServerIsOffline() = runTest {
        // CatalogException.Offline = network unreachable → must return false so the offline banner shows.
        val cap = FakeCatalog(findError = CatalogException.Offline(RuntimeException("connection refused")))
        val repo = makeRepo(cap)
        assertFalse(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun refreshForSourceReturnsTrueWhenServerReturnsAuthError() = runTest {
        // CatalogException.Auth means the server IS reachable. Offline banner must not appear.
        val cap = FakeCatalog(findError = CatalogException.Auth())
        val repo = makeRepo(cap)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    @Test
    fun refreshForSourceReturnsTrueForNonCapabilitySource() = runTest {
        // When the active catalog does not implement PlaylistsCapability, refreshForSource must
        // return true (no error surfaced) without touching the network.
        val repo = makeRepo(catalog = null)
        assertTrue(repo.refreshForSource("src-1", "lib-1"))
    }

    // ── Local store fallback (no PlaylistsCapability active) ──────────────────

    @Test
    fun addToToReadForSourcePersistsLocallyWhenNoCapability() = runTest {
        val store = FakeLocalToReadStore()
        val repo = makeRepo(catalog = null, localStore = store)
        assertTrue(repo.addToToReadForSource("src-1", "item-42", "lib-1"))
        assertTrue(store.isInToRead("lib-1", "item-42"))
    }

    @Test
    fun removeFromToReadForSourceRemovesLocallyWhenNoCapability() = runTest {
        val store = FakeLocalToReadStore()
        store.add("lib-1", "item-42")
        val repo = makeRepo(catalog = null, localStore = store)
        assertTrue(repo.removeFromToReadForSource("src-1", "item-42", "lib-1"))
        assertFalse(store.isInToRead("lib-1", "item-42"))
    }

    @Test
    fun isInToReadForSourceReadsLocalStoreWhenNoCapability() = runTest {
        val store = FakeLocalToReadStore()
        val repo = makeRepo(catalog = null, localStore = store)
        assertFalse(repo.isInToReadForSource("src-1", "item-42", "lib-1"))
        store.add("lib-1", "item-42")
        assertTrue(repo.isInToReadForSource("src-1", "item-42", "lib-1"))
    }

    // ── observeToReadItemIds unions cache and local store ─────────────────────

    @Test
    fun observeToReadItemIdsCombinesCacheAndLocalStore() = runTest {
        val store = FakeLocalToReadStore()
        store.add("lib-1", "local-item")
        val cap = FakeCatalog(mapOf("lib-1" to listOf(playlist("pl-1", "To Read", listOf("server-item")))))
        val repo = makeRepo(cap, store)
        repo.refreshForSource("src-1", "lib-1")
        val ids = repo.observeToReadItemIds("lib-1").first()
        assertTrue("server-item" in ids)
        assertTrue("local-item" in ids)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        val findError: Throwable? = null,
    ) : Catalog, PlaylistsCapability {
        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
        override suspend fun listPlaylists(rootId: String): List<CatalogPlaylist> = playlistsByLibrary[rootId].orEmpty()
        override suspend fun findPlaylist(rootId: String, name: String): CatalogPlaylist? {
            findError?.let { throw it }
            return playlistsByLibrary[rootId].orEmpty().firstOrNull { it.name == name }
        }
        override suspend fun createPlaylist(rootId: String, name: String, initialItemId: String?): CatalogPlaylist =
            CatalogPlaylist(
                id = "pl-new", rootId = rootId, name = name,
                bookCount = if (initialItemId != null) 1 else 0,
                itemIds = if (initialItemId != null) listOf(initialItemId) else emptyList(),
            )
        override suspend fun addItemToPlaylist(playlistId: String, itemId: String) = Unit
        override suspend fun removeItemFromPlaylist(playlistId: String, itemId: String) = Unit
    }

    private class FakeLocalToReadStore : LocalToReadStore {
        private val map = mutableMapOf<String, Set<String>>()
        private val flow = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        override fun observeItemIds(libraryId: String): Flow<Set<String>> =
            flow.map { it[libraryId].orEmpty() }
        override suspend fun isInToRead(libraryId: String, libraryItemId: String): Boolean =
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
