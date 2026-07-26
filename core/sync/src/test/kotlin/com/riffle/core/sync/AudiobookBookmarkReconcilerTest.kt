package com.riffle.core.sync

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBookmarkReconcilerTest {

    private class FakeSyncStore : AudiobookBookmarkSyncStore {
        val rows = mutableListOf<SyncableAudiobookBookmark>()

        override suspend fun allForItemIncludingDeleted(sourceId: String, itemId: String) =
            rows.filter { it.sourceId == sourceId && it.itemId == itemId }

        override suspend fun upsert(bookmark: SyncableAudiobookBookmark) {
            rows.removeAll { it.id == bookmark.id }
            rows.add(bookmark)
        }

        override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean {
            val idx = rows.indexOfFirst { it.id == id && it.localUpdatedAt == ifLocalUpdatedAt }
            if (idx < 0) return false
            rows[idx] = rows[idx].copy(lastSyncedAt = serverStamp, localUpdatedAt = serverStamp)
            return true
        }

        override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Boolean {
            val row = rows.firstOrNull { it.id == id && it.deleted && it.localUpdatedAt == ifLocalUpdatedAt }
                ?: return false
            rows.removeAll { it.id == id }
            return true
        }

        override suspend fun hardDelete(id: String) {
            rows.removeAll { it.id == id }
        }

        fun getById(id: String) = rows.firstOrNull { it.id == id }
    }

    private class FakeCatalog(
        var listResult: Result<List<CatalogBookmark>> = Result.success(emptyList()),
    ) : Catalog, BookmarksCapability {
        data class Call(val kind: String, val itemId: String, val timeSec: Int, val title: String)
        val calls = mutableListOf<Call>()
        var createOk: Boolean = true
        var renameOk: Boolean = true
        var deleteOk: Boolean = true

        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)

        override suspend fun listAllBookmarks(): List<CatalogBookmark> = listResult.getOrThrow()

        override suspend fun createBookmark(itemId: String, timeSec: Int, title: String): CatalogBookmark {
            calls += Call("create", itemId, timeSec, title)
            if (!createOk) throw RuntimeException("boom")
            return CatalogBookmark(itemId, timeSec, title, createdAt = 0L)
        }

        override suspend fun deleteBookmark(itemId: String, timeSec: Int) {
            calls += Call("delete", itemId, timeSec, "")
            if (!deleteOk) throw RuntimeException("boom")
        }

        override suspend fun renameBookmark(itemId: String, timeSec: Int, newTitle: String): CatalogBookmark {
            calls += Call("update", itemId, timeSec, newTitle)
            if (!renameOk) throw RuntimeException("boom")
            return CatalogBookmark(itemId, timeSec, newTitle, createdAt = 0L)
        }
    }

    private class FakeRegistry(private val catalog: Catalog) : CatalogRegistry {
        override suspend fun forActive(): Catalog = catalog
        override suspend fun forSource(source: Source): Catalog = catalog
        override suspend fun forSourceId(sourceId: String): Catalog = catalog
    }

    private val now = { 1000L }
    private fun counterIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun reconciler(store: FakeSyncStore, catalog: FakeCatalog) =
        AudiobookBookmarkReconciler(store, FakeRegistry(catalog), now = now, newId = counterIds())

    private suspend fun AudiobookBookmarkReconciler.run() = reconcile("s1", "i1")

    private fun bookmark(
        id: String,
        positionSec: Double,
        title: String,
        localUpdatedAt: Long,
        lastSyncedAt: Long,
        deleted: Boolean = false,
        createdAt: Long = 500L,
    ) = SyncableAudiobookBookmark(
        id = id, sourceId = "s1", itemId = "i1", positionSec = positionSec, title = title,
        createdAt = createdAt, localUpdatedAt = localUpdatedAt, lastSyncedAt = lastSyncedAt,
        deleted = deleted,
    )

    @Test fun pushCreate() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 12.4, "Intro", localUpdatedAt = 800L, lastSyncedAt = 0L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 12, "Intro", 500L))))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue("created row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushRename() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 30.0, "New name", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 30, "New name", 500L))))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("update", "i1", 30, "New name")), cat.calls.filter { it.kind == "update" })
        assertTrue(cat.calls.none { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue("renamed row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushDelete() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog()
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("delete", "i1", 45, "")), cat.calls.filter { it.kind == "delete" })
        assertNull("confirmed delete must be hard-removed", store.getById("a"))
    }

    @Test fun pushDeleteNetworkFailureKeepsTombstone() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog().apply { deleteOk = false }
        reconciler(store, cat).run()

        val row = store.getById("a")!!
        assertEquals(true, row.deleted)
        assertTrue("tombstone stays dirty for retry", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun pullInsert() = runTest {
        val store = FakeSyncStore()
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 77, "From source", 1234L))))
        reconciler(store, cat).run()

        val row = store.allForItemIncludingDeleted("s1", "i1").single()
        assertEquals(77.0, row.positionSec, 0.0001)
        assertEquals("From source", row.title)
        assertEquals(1234L, row.createdAt)
        assertEquals(false, row.deleted)
        assertTrue("source-sourced row is clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pullRemovesCleanRowAbsentFromServer() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 20.0, "stale", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(emptyList()))
        reconciler(store, cat).run()

        assertNull("clean row missing from source must be removed", store.getById("a"))
    }

    @Test fun pullDoesNotClobberDirtyRows() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("create", 20.0, "pending", localUpdatedAt = 900L, lastSyncedAt = 0L))
        store.upsert(bookmark("rename", 50.0, "local title", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 50, "source title", 500L))))
        cat.createOk = false
        cat.renameOk = false
        reconciler(store, cat).run()

        assertNotNull("dirty pending create must survive pull", store.getById("create"))
        val renameRow = store.getById("rename")!!
        assertEquals("dirty local title must NOT be clobbered", "local title", renameRow.title)
    }

    @Test fun listBookmarksNetworkErrorSkipsPullButPushesHappen() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 12.0, "Intro", localUpdatedAt = 800L, lastSyncedAt = 0L))
        store.upsert(bookmark("clean", 99.0, "keep", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.failure(RuntimeException("down")))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        assertNotNull("pull skipped: clean row must NOT be removed", store.getById("clean"))
    }

    @Test fun crossItemIsolation() = runTest {
        val store = FakeSyncStore()
        val cat = FakeCatalog(
            listResult = Result.success(
                listOf(
                    CatalogBookmark("OTHER", 10, "other item", 1L),
                    CatalogBookmark("i1", 20, "ours", 2L),
                ),
            ),
        )
        reconciler(store, cat).run()

        val rows = store.allForItemIncludingDeleted("s1", "i1")
        assertEquals(1, rows.size)
        assertEquals("ours", rows.single().title)
        assertTrue(store.rows.none { it.itemId == "OTHER" })
    }
}
