package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
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
            scope = CoroutineScope(Dispatchers.Unconfined),
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
}
