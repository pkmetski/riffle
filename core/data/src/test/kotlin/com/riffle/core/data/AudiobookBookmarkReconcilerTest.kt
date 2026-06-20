package com.riffle.core.data

import com.riffle.core.database.AudiobookBookmarkDao
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsBookmarkListResult
import com.riffle.core.network.AbsBookmarkResult
import com.riffle.core.network.NetworkAbsBookmark
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

    // A fake DAO that faithfully implements the compare-and-clear / hard-delete semantics
    // so the reconciler's clean/dirty transitions can be asserted end-to-end.
    private class FakeDao : AudiobookBookmarkDao {
        val rows = MutableStateFlow<List<AudiobookBookmarkEntity>>(emptyList())

        override suspend fun upsert(entity: AudiobookBookmarkEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override fun observeForItem(serverId: String, itemId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list ->
                list.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
                    .sortedBy { it.positionSec }
            }

        override fun observeForServer(serverId: String): Flow<List<AudiobookBookmarkEntity>> =
            rows.map { list -> list.filter { it.serverId == serverId && !it.deleted }.sortedBy { it.positionSec } }

        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }

        override suspend fun allForItem(serverId: String, itemId: String) =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId }

        override suspend fun dirtyForServer(serverId: String) =
            rows.value.filter { it.serverId == serverId && it.localUpdatedAt > it.lastSyncedAt }

        override suspend fun serversWithDirtyRows() =
            rows.value.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.serverId }.distinct()

        override fun observeDirtyCountForItem(serverId: String, itemId: String): Flow<Int> =
            rows.map { list ->
                list.count { it.serverId == serverId && it.itemId == itemId && it.localUpdatedAt > it.lastSyncedAt }
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

    private class FakeApi(
        var listResult: AbsBookmarkListResult = AbsBookmarkListResult.Success(emptyList()),
    ) : AbsBookmarkApi {
        data class Call(val kind: String, val itemId: String, val timeSec: Int, val title: String)

        val calls = mutableListOf<Call>()
        var createResult: AbsBookmarkResult = AbsBookmarkResult.Success(NetworkAbsBookmark("", "", 0, 0))
        var updateResult: AbsBookmarkResult = AbsBookmarkResult.Success(NetworkAbsBookmark("", "", 0, 0))
        var deleteResult: AbsBookmarkResult = AbsBookmarkResult.Success(NetworkAbsBookmark("", "", 0, 0))

        override suspend fun createBookmark(
            baseUrl: String,
            itemId: String,
            timeSec: Int,
            title: String,
            token: String,
            insecureAllowed: Boolean,
        ): AbsBookmarkResult {
            calls += Call("create", itemId, timeSec, title)
            return createResult
        }

        override suspend fun updateBookmark(
            baseUrl: String,
            itemId: String,
            timeSec: Int,
            title: String,
            token: String,
            insecureAllowed: Boolean,
        ): AbsBookmarkResult {
            calls += Call("update", itemId, timeSec, title)
            return updateResult
        }

        override suspend fun deleteBookmark(
            baseUrl: String,
            itemId: String,
            timeSec: Int,
            token: String,
            insecureAllowed: Boolean,
        ): AbsBookmarkResult {
            calls += Call("delete", itemId, timeSec, "")
            return deleteResult
        }

        override suspend fun listBookmarks(
            baseUrl: String,
            token: String,
            insecureAllowed: Boolean,
        ): AbsBookmarkListResult = listResult
    }

    private val now = { 1000L }
    private fun counterIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun reconciler(dao: FakeDao, api: FakeApi) =
        AudiobookBookmarkReconciler(dao, api, now = now, newId = counterIds())

    private suspend fun AudiobookBookmarkReconciler.run() =
        reconcile("s1", "i1", "http://abs", "tok", insecureAllowed = false)

    @Test fun pushCreate() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 12.4, title = "Intro",
                createdAt = 500L, localUpdatedAt = 800L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        // Server echoes the bookmark back so the pull doesn't remove the now-clean row.
        val api = FakeApi(listResult = AbsBookmarkListResult.Success(listOf(NetworkAbsBookmark("i1", "Intro", 12, 500L))))
        reconciler(dao, api).run()

        assertEquals(listOf(FakeApi.Call("create", "i1", 12, "Intro")), api.calls.filter { it.kind == "create" })
        val row = dao.getById("a")!!
        assertTrue("created row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushRename() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 30.0, title = "New name",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val api = FakeApi(listResult = AbsBookmarkListResult.Success(listOf(NetworkAbsBookmark("i1", "New name", 30, 500L))))
        reconciler(dao, api).run()

        assertEquals(listOf(FakeApi.Call("update", "i1", 30, "New name")), api.calls.filter { it.kind == "update" })
        assertTrue(api.calls.none { it.kind == "create" })
        val row = dao.getById("a")!!
        assertTrue("renamed row must become clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pushDelete() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 45.0, title = "x",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true,
            ),
        )
        val api = FakeApi()
        reconciler(dao, api).run()

        assertEquals(listOf(FakeApi.Call("delete", "i1", 45, "")), api.calls.filter { it.kind == "delete" })
        assertNull("confirmed delete must be hard-removed", dao.getById("a"))
    }

    @Test fun pushDeleteNetworkFailureKeepsTombstone() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 45.0, title = "x",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = true,
            ),
        )
        val api = FakeApi()
        api.deleteResult = AbsBookmarkResult.NetworkError(RuntimeException("boom"))
        reconciler(dao, api).run()

        val row = dao.getById("a")!!
        assertEquals(true, row.deleted)
        assertTrue("tombstone stays dirty for retry", row.localUpdatedAt > row.lastSyncedAt)
    }

    @Test fun pullInsert() = runTest {
        val dao = FakeDao()
        val api = FakeApi(listResult = AbsBookmarkListResult.Success(listOf(NetworkAbsBookmark("i1", "From server", 77, 1234L))))
        reconciler(dao, api).run()

        val row = dao.allForItem("s1", "i1").single()
        assertEquals(77.0, row.positionSec, 0.0001)
        assertEquals("From server", row.title)
        assertEquals(1234L, row.createdAt)
        assertEquals(false, row.deleted)
        assertTrue("server-sourced row is clean", row.localUpdatedAt <= row.lastSyncedAt)
    }

    @Test fun pullRemovesCleanRowAbsentFromServer() = runTest {
        val dao = FakeDao()
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 20.0, title = "stale",
                createdAt = 500L, localUpdatedAt = 600L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val api = FakeApi(listResult = AbsBookmarkListResult.Success(emptyList()))
        reconciler(dao, api).run()

        assertNull("clean row missing from server must be removed", dao.getById("a"))
    }

    @Test fun pullDoesNotClobberDirtyRows() = runTest {
        val dao = FakeDao()
        // Dirty local create whose time is absent server-side -> must NOT be removed.
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "create", serverId = "s1", itemId = "i1", positionSec = 20.0, title = "pending",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        // Dirty rename whose server title differs -> server title must NOT be applied.
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "rename", serverId = "s1", itemId = "i1", positionSec = 50.0, title = "local title",
                createdAt = 500L, localUpdatedAt = 900L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        // Server returns the rename's time with a DIFFERENT title; the create's time is absent.
        val api = FakeApi(
            listResult = AbsBookmarkListResult.Success(listOf(NetworkAbsBookmark("i1", "server title", 50, 500L))),
        )
        // Pushes fail (network) so both rows stay DIRTY through the pull — that's the scenario
        // under test: the pull must not clobber pending local intent.
        api.createResult = AbsBookmarkResult.NetworkError(RuntimeException("down"))
        api.updateResult = AbsBookmarkResult.NetworkError(RuntimeException("down"))
        reconciler(dao, api).run()

        val createRow = dao.getById("create")
        assertNotNull("dirty pending create must survive pull", createRow)
        val renameRow = dao.getById("rename")!!
        assertEquals("dirty local title must NOT be clobbered", "local title", renameRow.title)
    }

    @Test fun listBookmarksNetworkErrorSkipsPullButPushesHappen() = runTest {
        val dao = FakeDao()
        // A dirty create to push.
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "a", serverId = "s1", itemId = "i1", positionSec = 12.0, title = "Intro",
                createdAt = 500L, localUpdatedAt = 800L, lastSyncedAt = 0L, deleted = false,
            ),
        )
        // A clean row that, if the pull ran, would be removed (absent server-side). It must survive.
        dao.upsert(
            AudiobookBookmarkEntity(
                id = "clean", serverId = "s1", itemId = "i1", positionSec = 99.0, title = "keep",
                createdAt = 500L, localUpdatedAt = 600L, lastSyncedAt = 600L, deleted = false,
            ),
        )
        val api = FakeApi(listResult = AbsBookmarkListResult.NetworkError(RuntimeException("down")))
        reconciler(dao, api).run()

        assertEquals(listOf(FakeApi.Call("create", "i1", 12, "Intro")), api.calls.filter { it.kind == "create" })
        assertNotNull("pull skipped: clean row must NOT be removed", dao.getById("clean"))
    }

    @Test fun crossItemIsolation() = runTest {
        val dao = FakeDao()
        val api = FakeApi(
            listResult = AbsBookmarkListResult.Success(
                listOf(
                    NetworkAbsBookmark("OTHER", "other item", 10, 1L),
                    NetworkAbsBookmark("i1", "ours", 20, 2L),
                ),
            ),
        )
        reconciler(dao, api).run()

        val rows = dao.allForItem("s1", "i1")
        assertEquals(1, rows.size)
        assertEquals("ours", rows.single().title)
        // No row inserted for the OTHER item.
        assertTrue(dao.rows.value.none { it.itemId == "OTHER" })
    }
}
