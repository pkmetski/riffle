package com.riffle.core.data

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
import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookBookmarkReconcilerTest {

    private class FakeDao : AudiobookBookmarkDao {
        val rows = MutableStateFlow<List<AudiobookBookmarkEntity>>(emptyList())

        override suspend fun upsert(entity: AudiobookBookmarkEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override fun observeForItem(sourceId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list ->
                list.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                    .sortedBy { it.positionSec }
            }

        override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list -> list.filter { it.sourceId == sourceId && !it.deleted }.sortedBy { it.positionSec } }

        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }

        override suspend fun allForItem(sourceId: String, itemId: String) =
            rows.value.filter { it.sourceId == sourceId && it.itemId == itemId }

        override suspend fun dirtyForSource(sourceId: String) =
            rows.value.filter { it.sourceId == sourceId && it.localUpdatedAt > it.lastSyncedAt }

        override suspend fun sourcesWithDirtyRows() =
            rows.value.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.sourceId }.distinct()

        override fun observeDirtyCountForItem(sourceId: String, itemId: String): Flow<Int> =
            rows.map { list ->
                list.count { it.sourceId == sourceId && it.itemId == itemId && it.localUpdatedAt > it.lastSyncedAt }
            }

        override suspend fun confirmPushedIfUnchanged(id: String, serverStamp: Long, ifLocalUpdatedAt: Long): Int {
            val row = rows.value.firstOrNull { it.id == id && it.localUpdatedAt == ifLocalUpdatedAt } ?: return 0
            rows.value = rows.value.map {
                if (it.id == id) it.copy(lastSyncedAt = serverStamp, localUpdatedAt = serverStamp) else it
            }
            return 1
        }

        override suspend fun hardDeleteIfUnchanged(id: String, ifLocalUpdatedAt: Long): Int {
            val row = rows.value.firstOrNull { it.id == id && it.deleted && it.localUpdatedAt == ifLocalUpdatedAt }
                ?: return 0
            rows.value = rows.value.filterNot { it.id == id }
            return 1
        }

        override suspend fun hardDelete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
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
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int) = emptyList<CatalogItem>()
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

    private fun reconciler(dao: FakeDao, catalog: FakeCatalog) =
        AudiobookBookmarkReconciler(dao, FakeRegistry(catalog), now = now, newId = counterIds())

    private suspend fun AudiobookBookmarkReconciler.run() = reconcile("s1", "i1")

    @Test fun pushCreate() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 12.4, title = "Intro",
                createdAt = 500L, localUpdatedAt = 800L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 12, "Intro", 500L))))
        reconciler(dao, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        val row = dao.getById("a")!!
        assertTrue("created row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushRename() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 30.0, title = "New name",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 30, "New name", 500L))))
        reconciler(dao, cat).run()

        assertEquals(listOf(FakeCatalog.Call("update", "i1", 30, "New name")), cat.calls.filter { it.kind == "update" })
        assertTrue(cat.calls.none { it.kind == "create" })
        val row = dao.getById("a")!!
        assertTrue("renamed row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushDelete() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 45.0, title = "x",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true,
            ),
        )
        val cat = FakeCatalog()
        reconciler(dao, cat).run()

        assertEquals(listOf(FakeCatalog.Call("delete", "i1", 45, "")), cat.calls.filter { it.kind == "delete" })
        assertNull("confirmed delete must be hard-removed", dao.getById("a"))
    }

    @Test fun pushDeleteNetworkFailureKeepsTombstone() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 45.0, title = "x",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true,
            ),
        )
        val cat = FakeCatalog().apply { deleteOk = false }
        reconciler(dao, cat).run()

        val row = dao.getById("a")!!
        assertEquals(true, row.deleted)
        assertTrue("tombstone stays dirty for retry", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun pullInsert() = runTest {
        val dao = FakeDao()
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 77, "From source", 1234L))))
        reconciler(dao, cat).run()

        val row = dao.allForItem("s1", "i1").single()
        assertEquals(77.0, row.positionSec, 0.0001)
        assertEquals("From source", row.title)
        assertEquals(1234L, row.createdAt)
        assertEquals(false, row.deleted)
        assertTrue("source-sourced row is clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pullRemovesCleanRowAbsentFromServer() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 20.0, title = "stale",
                createdAt = 500L, localUpdatedAt = 600L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val cat = FakeCatalog(listResult = Result.success(emptyList()))
        reconciler(dao, cat).run()

        assertNull("clean row missing from source must be removed", dao.getById("a"))
    }

    @Test fun pullDoesNotClobberDirtyRows() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "create", sourceId = "s1", itemId = "i1", positionSec = 20.0, title = "pending",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "rename", sourceId = "s1", itemId = "i1", positionSec = 50.0, title = "local title",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val cat = FakeCatalog(listResult = Result.success(listOf(CatalogBookmark("i1", 50, "source title", 500L))))
        cat.createOk = false
        cat.renameOk = false
        reconciler(dao, cat).run()

        val createRow = dao.getById("create")
        assertNotNull("dirty pending create must survive pull", createRow)
        val renameRow = dao.getById("rename")!!
        assertEquals("dirty local title must NOT be clobbered", "local title", renameRow.title)
    }

    @Test fun listBookmarksNetworkErrorSkipsPullButPushesHappen() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", sourceId = "s1", itemId = "i1", positionSec = 12.0, title = "Intro",
                createdAt = 500L, localUpdatedAt = 800L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "clean", sourceId = "s1", itemId = "i1", positionSec = 99.0, title = "keep",
                createdAt = 500L, localUpdatedAt = 600L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val cat = FakeCatalog(listResult = Result.failure(RuntimeException("down")))
        reconciler(dao, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        assertNotNull("pull skipped: clean row must NOT be removed", dao.getById("clean"))
    }

    @Test fun crossItemIsolation() = runTest {
        val dao = FakeDao()
        val cat = FakeCatalog(
            listResult = Result.success(
                listOf(
                    CatalogBookmark("OTHER", 10, "other item", 1L),
                    CatalogBookmark("i1", 20, "ours", 2L),
                ),
            ),
        )
        reconciler(dao, cat).run()

        val rows = dao.allForItem("s1", "i1")
        assertEquals(1, rows.size)
        assertEquals("ours", rows.single().title)
        assertTrue(dao.rows.value.none { it.itemId == "OTHER" })
    }
}
