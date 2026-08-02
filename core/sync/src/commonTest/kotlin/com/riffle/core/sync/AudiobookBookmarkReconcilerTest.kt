package com.riffle.core.sync

import com.riffle.core.common.Clock
import com.riffle.core.common.RandomProvider
import com.riffle.core.domain.AudiobookBookmarkSyncStore
import com.riffle.core.domain.SyncableAudiobookBookmark
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        var listResult: Result<List<RemoteBookmark>> = Result.success(emptyList()),
    ) : BookmarkRemote {
        data class Call(val kind: String, val itemId: String, val timeSec: Int, val title: String)
        val calls = mutableListOf<Call>()
        var createOk: Boolean = true
        var renameOk: Boolean = true
        var deleteOk: Boolean = true

        override suspend fun listAll(): List<RemoteBookmark> = listResult.getOrThrow()

        override suspend fun create(itemId: String, timeSec: Int, title: String) {
            calls += Call("create", itemId, timeSec, title)
            if (!createOk) throw RuntimeException("boom")
        }

        override suspend fun delete(itemId: String, timeSec: Int) {
            calls += Call("delete", itemId, timeSec, "")
            if (!deleteOk) throw RuntimeException("boom")
        }

        override suspend fun rename(itemId: String, timeSec: Int, title: String) {
            calls += Call("update", itemId, timeSec, title)
            if (!renameOk) throw RuntimeException("boom")
        }
    }

    private val clock = object : Clock {
        override fun nowMs() = 1000L
        override fun nowNs() = 1_000_000_000L
    }

    private fun counterIds(): RandomProvider {
        var n = 0
        return RandomProvider { "gen-${n++}" }
    }

    private fun reconciler(store: FakeSyncStore, catalog: FakeCatalog) =
        AudiobookBookmarkReconciler(
            store = store,
            sourceResolver = SyncSourceResolver {
                object : SyncSource {
                    override val supportsEbookProgress = false
                    override val supportsAudiobookProgress = false
                    override val bookmarks = catalog
                }
            },
            clock = clock,
            random = counterIds(),
        )

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
        val cat = FakeCatalog(listResult = Result.success(listOf(RemoteBookmark("i1", 12, "Intro", 500L))))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue(row.localUpdatedAt <= row.lastSyncedAt, "created row must become clean")
    }

    @Test fun pushRename() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 30.0, "New name", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(RemoteBookmark("i1", 30, "New name", 500L))))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("update", "i1", 30, "New name")), cat.calls.filter { it.kind == "update" })
        assertTrue(cat.calls.none { it.kind == "create" })
        val row = store.getById("a")!!
        assertTrue(row.localUpdatedAt <= row.lastSyncedAt, "renamed row must become clean")
    }

    @Test fun pushDelete() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog()
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("delete", "i1", 45, "")), cat.calls.filter { it.kind == "delete" })
        assertNull(store.getById("a"), "confirmed delete must be hard-removed")
    }

    @Test fun pushDeleteNetworkFailureKeepsTombstone() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 45.0, "x", localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true))
        val cat = FakeCatalog().apply { deleteOk = false }
        reconciler(store, cat).run()

        val row = store.getById("a")!!
        assertEquals(true, row.deleted)
        assertTrue(row.localUpdatedAt > row.lastSyncedAt, "tombstone stays dirty for retry")
    }

    @Test fun pullInsert() = runTest {
        val store = FakeSyncStore()
        val cat = FakeCatalog(listResult = Result.success(listOf(RemoteBookmark("i1", 77, "From source", 1234L))))
        reconciler(store, cat).run()

        val row = store.allForItemIncludingDeleted("s1", "i1").single()
        assertEquals("gen-0", row.id)
        assertEquals(77.0, row.positionSec, 0.0001)
        assertEquals("From source", row.title)
        assertEquals(1234L, row.createdAt)
        assertEquals(1000L, row.localUpdatedAt)
        assertEquals(1000L, row.lastSyncedAt)
        assertEquals(false, row.deleted)
        assertTrue(row.localUpdatedAt <= row.lastSyncedAt, "source-sourced row is clean")
    }

    @Test fun pullRemovesCleanRowAbsentFromServer() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 20.0, "stale", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(emptyList()))
        reconciler(store, cat).run()

        assertNull(store.getById("a"), "clean row missing from source must be removed")
    }

    @Test fun pullDoesNotClobberDirtyRows() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("create", 20.0, "pending", localUpdatedAt = 900L, lastSyncedAt = 0L))
        store.upsert(bookmark("rename", 50.0, "local title", localUpdatedAt = 900L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.success(listOf(RemoteBookmark("i1", 50, "source title", 500L))))
        cat.createOk = false
        cat.renameOk = false
        reconciler(store, cat).run()

        assertNotNull(store.getById("create"), "dirty pending create must survive pull")
        val renameRow = store.getById("rename")!!
        assertEquals("local title", renameRow.title, "dirty local title must NOT be clobbered")
    }

    @Test fun listBookmarksNetworkErrorSkipsPullButPushesHappen() = runTest {
        val store = FakeSyncStore()
        store.upsert(bookmark("a", 12.0, "Intro", localUpdatedAt = 800L, lastSyncedAt = 0L))
        store.upsert(bookmark("clean", 99.0, "keep", localUpdatedAt = 600L, lastSyncedAt = 600L))
        val cat = FakeCatalog(listResult = Result.failure(RuntimeException("down")))
        reconciler(store, cat).run()

        assertEquals(listOf(FakeCatalog.Call("create", "i1", 12, "Intro")), cat.calls.filter { it.kind == "create" })
        assertNotNull(store.getById("clean"), "pull skipped: clean row must NOT be removed")
    }

    @Test fun crossItemIsolation() = runTest {
        val store = FakeSyncStore()
        val cat = FakeCatalog(
            listResult = Result.success(
                listOf(
                    RemoteBookmark("OTHER", 10, "other item", 1L),
                    RemoteBookmark("i1", 20, "ours", 2L),
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
