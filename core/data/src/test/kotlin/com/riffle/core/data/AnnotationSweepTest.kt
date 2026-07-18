package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSweepTest {

    @Test
    fun `unconfigured target is silent no-op and reports nothing`() = runTest {
        val status = AnnotationSyncStatusStore()
        val dao = FakeAnnotationDao()
        val sweep = AnnotationSweep(
            targetProvider = { null },
            annotationDao = dao,
            deviceIdStore = FakeDeviceIdStore("dev-A"),
            deviceLabelResolver = FakeDeviceLabelResolver("test-label"),
            sourceRepository = FakeSourceRepository(
                absUserIds = mapOf("srv-A" to "abs-user-A"),
                usernames = mapOf("srv-A" to "alice"),
            ),
            statusStore = status,
            clock = { 1000L },
        )

        val outcome = sweep.run()

        assertEquals(CycleOutcome.NeverRun, status.lastCycleOutcome.value)
        assertEquals(0, dao.markSyncedCalls)
        // Unconfigured target returns null so the worker treats it as success — there's nothing
        // to retry until the user configures a target.
        assertEquals(null, outcome)
    }

    @Test
    fun `push success stamps lastSyncedAt and reports Success`() = runTest {
        val now = 5_000L
        val status = AnnotationSyncStatusStore()
        val target = FakeTarget()
        val dao = FakeAnnotationDao(
            dirty = listOf(AnnotationDao.DirtySourceItem("srv-A", "item-1")),
            rowsByItem = mapOf(
                ("srv-A" to "item-1") to listOf(
                    annotation("ann-1", "srv-A", "item-1", updatedAt = 100L, lastSyncedAt = 0L),
                    annotation("ann-2", "srv-A", "item-1", updatedAt = 200L, lastSyncedAt = 0L),
                )
            ),
        )
        val sweep = AnnotationSweep(
            targetProvider = { target },
            annotationDao = dao,
            deviceIdStore = FakeDeviceIdStore("dev-A"),
            deviceLabelResolver = FakeDeviceLabelResolver("test-label"),
            sourceRepository = FakeSourceRepository(
                absUserIds = mapOf("srv-A" to "abs-user-A"),
                usernames = mapOf("srv-A" to "alice"),
            ),
            statusStore = status,
            bookTitleProvider = { _, _ -> "Project Hail Mary" },
            clock = { now },
        )

        val returned = sweep.run()
        assertTrue("run() must return the reported outcome", returned is CycleOutcome.Success)

        assertEquals(1, target.writes.size)
        val write = target.writes.single()
        assertEquals("abs-user-A", write.namespace)
        assertEquals("item-1", write.itemId)
        assertEquals("annotations-dev-A.jsonld", write.filename)
        assertTrue(write.content.contains("ann-1"))
        assertTrue(write.content.contains("ann-2"))
        // Verify the file body carries the file-header object written by AnnotationFileHeaderCodec.
        assertTrue("sweep output must contain riffle:FileHeader", write.content.contains("riffle:FileHeader"))
        // The per-file header is book-scoped only: deviceId + bookTitle. Device-scoped fields
        // (label/lastSyncedAt/username) live in the per-device sentinel — see below.
        assertTrue("sweep header must include bookTitle", write.content.contains("\"bookTitle\":\"Project Hail Mary\""))
        assertTrue("no label in per-file header", !write.content.contains("\"label\""))
        assertTrue("no lastSeenAt in per-file header", !write.content.contains("\"lastSeenAt\""))
        assertTrue("no username in per-file header", !write.content.contains("\"username\""))

        assertEquals(listOf("ann-1", "ann-2"), dao.lastMarkSyncedIds)
        assertEquals(now, dao.lastMarkSyncedAt)

        val outcome = status.lastCycleOutcome.value
        assertTrue(outcome is CycleOutcome.Success)
        assertEquals(now, (outcome as CycleOutcome.Success).atMs)

        // One sentinel was written per unique namespace touched this cycle, carrying
        // label/lastSyncedAt/username — peers read this to label the device and surface
        // an honest "Last synced …" timestamp.
        assertEquals(1, target.deviceMetaWrites.size)
        val sentinel = target.deviceMetaWrites.single()
        assertEquals("abs-user-A", sentinel.namespace)
        assertEquals("dev-A", sentinel.deviceId)
        val parsed = AnnotationDeviceMetaCodec.decode(sentinel.content)!!
        assertEquals("test-label", parsed.label)
        assertEquals("alice", parsed.username)
    }

    @Test
    fun `AuthFailed during push reports Failed Auth and leaves rows dirty`() = runTest {
        val status = AnnotationSyncStatusStore()
        val target = FakeTarget(writeException = AnnotationSyncException.AuthFailed(401))
        val dao = FakeAnnotationDao(
            dirty = listOf(AnnotationDao.DirtySourceItem("srv-A", "item-1")),
            rowsByItem = mapOf(("srv-A" to "item-1") to listOf(
                annotation("ann-1", "srv-A", "item-1", updatedAt = 100L, lastSyncedAt = 0L)
            )),
        )
        val sweep = AnnotationSweep(
            targetProvider = { target },
            annotationDao = dao,
            deviceIdStore = FakeDeviceIdStore("dev-A"),
            deviceLabelResolver = FakeDeviceLabelResolver("test-label"),
            sourceRepository = FakeSourceRepository(
                absUserIds = mapOf("srv-A" to "abs-user-A"),
                usernames = mapOf("srv-A" to "alice"),
            ),
            statusStore = status,
            clock = { 9_000L },
        )

        val returned = sweep.run()

        assertEquals(0, dao.markSyncedCalls)
        val outcome = status.lastCycleOutcome.value
        assertTrue(outcome is CycleOutcome.Failed.Auth)
        assertEquals(9_000L, (outcome as CycleOutcome.Failed.Auth).atMs)
        assertEquals(401, outcome.code)
        // run() returns the same outcome so the worker can map Failed.Auth → Result.failure().
        assertEquals(outcome, returned)
    }

    @Test
    fun `NetworkError aborts cycle and remaining books stay dirty`() = runTest {
        val status = AnnotationSyncStatusStore()
        val target = FakeTarget(writeException = AnnotationSyncException.NetworkError("eof"))
        val dao = FakeAnnotationDao(
            dirty = listOf(
                AnnotationDao.DirtySourceItem("srv-A", "item-1"),
                AnnotationDao.DirtySourceItem("srv-A", "item-2"),
            ),
            rowsByItem = mapOf(
                ("srv-A" to "item-1") to listOf(annotation("ann-1", "srv-A", "item-1", updatedAt = 1L, lastSyncedAt = 0L)),
                ("srv-A" to "item-2") to listOf(annotation("ann-2", "srv-A", "item-2", updatedAt = 1L, lastSyncedAt = 0L)),
            ),
        )
        val sweep = AnnotationSweep(
            targetProvider = { target },
            annotationDao = dao,
            deviceIdStore = FakeDeviceIdStore("dev-A"),
            deviceLabelResolver = FakeDeviceLabelResolver("test-label"),
            sourceRepository = FakeSourceRepository(
                absUserIds = mapOf("srv-A" to "abs-user-A"),
                usernames = mapOf("srv-A" to "alice"),
            ),
            statusStore = status,
            clock = { 1L },
        )

        val returned = sweep.run()

        // First book attempted, failed; second book never attempted.
        assertEquals(1, target.writes.size)
        assertEquals(0, dao.markSyncedCalls)
        assertTrue(status.lastCycleOutcome.value is CycleOutcome.Failed.Network)
        // run() returns Failed.Network so the worker maps it to Result.retry() — that's the queue
        // entry whose CONNECTED constraint re-fires when the device comes back online.
        assertTrue("run() must return Failed.Network", returned is CycleOutcome.Failed.Network)
    }

    @Test
    fun `book whose source has no absUserId is skipped`() = runTest {
        val status = AnnotationSyncStatusStore()
        val target = FakeTarget()
        val dao = FakeAnnotationDao(
            dirty = listOf(
                AnnotationDao.DirtySourceItem("srv-A", "item-1"),
                AnnotationDao.DirtySourceItem("srv-B", "item-2"),
            ),
            rowsByItem = mapOf(
                ("srv-A" to "item-1") to listOf(annotation("ann-A", "srv-A", "item-1", updatedAt = 1L, lastSyncedAt = 0L)),
                ("srv-B" to "item-2") to listOf(annotation("ann-B", "srv-B", "item-2", updatedAt = 1L, lastSyncedAt = 0L)),
            ),
        )
        // srv-B's absUserId not yet known — skipped, but the cycle still completes as Success
        // because every reachable book pushed cleanly.
        val sweep = AnnotationSweep(
            targetProvider = { target },
            annotationDao = dao,
            deviceIdStore = FakeDeviceIdStore("dev-A"),
            deviceLabelResolver = FakeDeviceLabelResolver("test-label"),
            sourceRepository = FakeSourceRepository(
                absUserIds = mapOf("srv-A" to "abs-user-A"),
                usernames = mapOf("srv-A" to "alice"),
            ),
            statusStore = status,
            clock = { 1L },
        )

        sweep.run()

        assertEquals(1, target.writes.size)
        assertEquals("abs-user-A", target.writes.single().namespace)
        assertTrue(status.lastCycleOutcome.value is CycleOutcome.Success)
    }

    // --- fakes ---

    private fun annotation(id: String, sourceId: String, itemId: String, updatedAt: Long, lastSyncedAt: Long) =
        AnnotationEntity(
            id = id, sourceId = sourceId, itemId = itemId,
            cfi = "epubcfi(/6/4!/4/2)", textSnippet = "x", chapterHref = "OEBPS/ch1.xhtml",
            createdAt = 0L, updatedAt = updatedAt,
            originDeviceId = "dev-A", lastModifiedByDeviceId = "dev-A",
            lastSyncedAt = lastSyncedAt,
        )

    private class FakeTarget(private val writeException: Throwable? = null) : AnnotationSyncTarget {
        data class WriteCall(val namespace: String, val itemId: String, val filename: String, val content: String)
        data class DeviceMetaWriteCall(val namespace: String, val deviceId: String, val content: String)
        val writes = mutableListOf<WriteCall>()
        val deviceMetaWrites = mutableListOf<DeviceMetaWriteCall>()
        override suspend fun list(namespace: String, itemId: String): List<String> = emptyList()
        override suspend fun read(namespace: String, itemId: String, filename: String): String? = null
        override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
            // Record the call before potentially throwing, so tests can verify which books were attempted.
            writes += WriteCall(namespace, itemId, filename, content.take(1024))
            writeException?.let { throw it }
        }
        override suspend fun delete(namespace: String, itemId: String, filename: String) {}
        override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? = null
        override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
            deviceMetaWrites += DeviceMetaWriteCall(namespace, deviceId, content.take(1024))
            writeException?.let { throw it }
        }
        override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {}
        override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing = NamespaceDeviceListing(emptyList())
        override suspend fun enumerateNamespaces(): List<NamespaceSummary> = emptyList()
        override suspend fun forgetNamespace(namespace: String): Int = 0
    }

    private class FakeAnnotationDao(
        private val dirty: List<AnnotationDao.DirtySourceItem> = emptyList(),
        private val rowsByItem: Map<Pair<String, String>, List<AnnotationEntity>> = emptyMap(),
    ) : StubAnnotationDao() {
        var markSyncedCalls = 0
        var lastMarkSyncedIds: List<String> = emptyList()
        var lastMarkSyncedAt: Long = 0L
        override suspend fun dirtySourceItems(): List<AnnotationDao.DirtySourceItem> = dirty
        override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity> =
            rowsByItem[sourceId to itemId].orEmpty()
        override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
            markSyncedCalls++
            lastMarkSyncedIds = ids
            lastMarkSyncedAt = syncedAt
        }
        override fun observeBooksWithHighlights(sourceId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.BookHighlightSummary>())
        override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int = 0
    }

    private class FakeDeviceIdStore(private val id: String) : DeviceIdStore {
        override suspend fun getOrCreate(): String = id
    }

    private class FakeDeviceLabelResolver(private val label: String) : DeviceLabelResolver {
        override suspend fun resolveLabel(deviceId: String) = label
        override fun deviceModel() = "test-model"
    }

    private class FakeSourceRepository(
        private val absUserIds: Map<String, String>,
        private val usernames: Map<String, String> = emptyMap(),
    ) : SourceRepository {
        override suspend fun ensureSyncNamespace(sourceId: String): com.riffle.core.domain.SyncNamespace =
            absUserIds[sourceId]?.let { com.riffle.core.domain.SyncNamespace.Configured(it) }
                ?: com.riffle.core.domain.SyncNamespace.LocalOnly("test fake")
        override suspend fun getById(sourceId: String): Source? = usernames[sourceId]?.let {
            Source(
                id = sourceId,
                url = SourceUrl.parse("https://example.test/")!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = it,
                absUserId = absUserIds[sourceId],
            )
        }
        override fun observeAll(): Flow<List<Source>> = emptyFlow()
        override suspend fun getActive(): Source? = null
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = error("not used")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}

/** Minimal AnnotationDao stub for tests — implements every required method to return empty. Tests
 *  override the few methods they care about. */
abstract class StubAnnotationDao : AnnotationDao {
    override fun observeForItem(sourceId: String, itemId: String) =
        kotlinx.coroutines.flow.flowOf(emptyList<AnnotationEntity>())
    override fun observeForSource(sourceId: String) =
        kotlinx.coroutines.flow.flowOf(emptyList<AnnotationEntity>())
    override suspend fun getForItem(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
    override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String) = emptyList<AnnotationEntity>()
    override suspend fun getById(id: String): AnnotationEntity? = null
    override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? = null
    override suspend fun findImageForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ): AnnotationEntity? = null
    override suspend fun upsert(entity: AnnotationEntity) {}
    override suspend fun upsertAll(annotations: List<AnnotationEntity>) {}
    override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {}
    override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {}
    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {}
    override fun observeAnnotationsByPosition(sourceId: String, itemId: String) =
        kotlinx.coroutines.flow.flowOf(emptyList<AnnotationEntity>())
    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {}
    override fun observePendingCountForBook(sourceId: String, itemId: String) = kotlinx.coroutines.flow.flowOf(0)
    override fun observePendingBookCountAcrossAll() = kotlinx.coroutines.flow.flowOf(0)
    override suspend fun dirtySourceItems() = emptyList<AnnotationDao.DirtySourceItem>()
    override suspend fun markSynced(ids: List<String>, syncedAt: Long) {}
    override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int = 0
    override suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int = 0

    override suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int = 0

    override fun observeBooksWithHighlights(sourceId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.BookHighlightSummary>())
    override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int = 0
}
