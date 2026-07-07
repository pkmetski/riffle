package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationSyncControllerReactiveTargetTest {

    @Test
    fun `controller looks up the target on each call so config changes apply without restart`() = runTest {
        val dao = NoOpAnnotationDao()
        val deviceIdStore = object : DeviceIdStore {
            override suspend fun getOrCreate() = "device-x"
        }
        var currentTarget: AnnotationSyncTarget? = null
        val controller = AnnotationSyncController(
            targetProvider = { currentTarget },
            mergeService = AnnotationMergeService(),
            annotationDao = dao,
            deviceIdStore = deviceIdStore,
            deviceLabelResolver = StubLabelResolver,
            scope = CoroutineScope(Dispatchers.Unconfined),
            statusStore = AnnotationSyncStatusStore(),
            sweepEnqueuer = {},
            nowIso = { "2026-01-01T00:00:00Z" },
        )

        // No target yet — syncOnOpen is a no-op (must not throw).
        controller.syncOnOpen("srv", "ns", "book")

        // Now a target appears (user configured WebDAV mid-session).
        val recording = RecordingTarget()
        currentTarget = recording

        controller.syncOnOpen("srv", "ns", "book")

        assertEquals(1, recording.listCalls)
    }
}

private class RecordingTarget : AnnotationSyncTarget {
    var listCalls = 0
    override suspend fun list(namespace: String, itemId: String): List<String> {
        listCalls++
        return emptyList()
    }
    override suspend fun read(namespace: String, itemId: String, filename: String): String? = null
    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {}
    override suspend fun delete(namespace: String, itemId: String, filename: String) {}
    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? = null
    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {}
    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {}
    override suspend fun enumerateDevices(namespace: String) = NamespaceDeviceListing(emptyList())
    override suspend fun enumerateNamespaces(): List<NamespaceSummary> = emptyList()
    override suspend fun forgetNamespace(namespace: String): Int = 0
}

private object StubLabelResolver : DeviceLabelResolver {
    override suspend fun resolveLabel(deviceId: String) = "test-device"
    override fun deviceModel() = "test-model"
}

private class NoOpAnnotationDao : AnnotationDao {
    override fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override fun observeForServer(serverId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> = emptyList()
    override suspend fun getAllForItemIncludingDeleted(serverId: String, itemId: String): List<AnnotationEntity> = emptyList()
    override suspend fun getById(id: String): AnnotationEntity? = null
    override suspend fun getByItemAndCfi(serverId: String, itemId: String, cfi: String): AnnotationEntity? = null
    override suspend fun upsert(entity: AnnotationEntity) = Unit
    override suspend fun upsertAll(annotations: List<AnnotationEntity>) = Unit
    override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) = Unit
    override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) = Unit
    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) = Unit
    override fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) = Unit
    override fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int> = flowOf(0)
    override fun observePendingBookCountAcrossAll(): Flow<Int> = flowOf(0)
    override suspend fun dirtyServerItems(): List<AnnotationDao.DirtyServerItem> = emptyList()
    override suspend fun markSynced(ids: List<String>, syncedAt: Long) = Unit
    override suspend fun purgeAgedTombstones(serverId: String, itemId: String, cutoff: Long): Int = 0
    override fun observeBooksWithHighlights(serverId: String): Flow<List<com.riffle.core.database.BookHighlightSummary>> = flowOf(emptyList())
}
