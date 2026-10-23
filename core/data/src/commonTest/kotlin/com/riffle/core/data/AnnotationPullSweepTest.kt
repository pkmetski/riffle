package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import com.riffle.core.sync.AnnotationSyncStatusStore
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationPullSweepTest {

    private val dao = PullTestAnnotationDao()
    private val deviceIdStore = PullFakeDeviceIdStore(OWN_DEVICE_ID)
    private val statusStore = AnnotationSyncStatusStore()

    private fun TestScope.newController(target: AnnotationSyncTarget?) = AnnotationSyncController(
        targetProvider = { target },
        mergeService = AnnotationMergeService(),
        annotationDao = dao,
        deviceIdStore = deviceIdStore,
        deviceLabelResolver = PullStubLabelResolver,
        scope = this,
        statusStore = statusStore,
        sweepEnqueuer = AnnotationSweepEnqueuer {},
        nowIso = { "2026-01-01T00:00:00Z" },
        clock = { NOW },
    )

    private fun TestScope.newSweep(target: AnnotationSyncTarget?, sources: List<Source>) =
        AnnotationPullSweep(
            targetProvider = { target },
            syncController = newController(target),
            sourceRepository = PullFakeSourceRepository(sources),
            statusStore = statusStore,
            clock = { NOW },
        )

    // ===== unconfigured target =====

    @Test
    fun unconfiguredTargetIsSilentNoopReturnsNull() = runTest {
        val sweep = newSweep(target = null, sources = listOf(source(SRV, NS)))

        val outcome = sweep.run()

        assertNull(outcome)
        assertEquals(CycleOutcome.NeverRun, statusStore.lastCycleOutcome.value)
        assertTrue(dao.upserts.isEmpty())
    }

    // ===== no remote peers =====

    @Test
    fun noRemotePeerFilesIsNoopReportsSuccess() = runTest {
        val target = PullRecordingTarget(
            deviceListing = NamespaceDeviceListing(emptyList()),
        )
        val sweep = newSweep(target, sources = listOf(source(SRV, NS)))

        val outcome = sweep.run()

        assertTrue(outcome is CycleOutcome.Success)
        assertTrue(dao.upserts.isEmpty())
        assertEquals(1, target.enumerateCalls)
    }

    // ===== peer annotations are merged =====

    @Test
    fun peerAnnotationsAreMergedIntoRoom() = runTest {
        val peerFileContent = jsonArrayOf(
            w3c("ann-1", updatedAt = 100L, deviceId = "peer-device"),
            w3c("ann-2", updatedAt = 200L, deviceId = "peer-device"),
        )
        val target = PullRecordingTarget(
            deviceListing = NamespaceDeviceListing(
                listOf(
                    DeviceFileSummary(
                        deviceId = "peer-device",
                        annotationFiles = listOf(AnnotationFileRef(ITEM_1, "annotations-peer-device.jsonld")),
                    ),
                ),
            ),
            fileContent = mapOf("annotations-peer-device.jsonld" to peerFileContent),
        )
        val sweep = newSweep(target, sources = listOf(source(SRV, NS)))

        sweep.run()

        assertEquals(setOf("ann-1", "ann-2"), dao.upserts.map { it.id }.toSet())
        assertTrue(dao.upserts.all { it.sourceId == SRV && it.itemId == ITEM_1 })
    }

    @Test
    fun peerAnnotationsAcrossMultipleItemsAreMerged() = runTest {
        val target = PullRecordingTarget(
            deviceListing = NamespaceDeviceListing(
                listOf(
                    DeviceFileSummary(
                        deviceId = "peer-device",
                        annotationFiles = listOf(
                            AnnotationFileRef(ITEM_1, "annotations-peer.jsonld"),
                            AnnotationFileRef(ITEM_2, "annotations-peer.jsonld"),
                        ),
                    ),
                ),
            ),
            // Item-scoped keys so the fake distinguishes ITEM_1 from ITEM_2 reads.
            // If the sweep ever routes ITEM_2's read to ITEM_1's key (or vice-versa), the
            // per-item annotation IDs in the assertions below will be wrong.
            fileContent = mapOf(
                "$ITEM_1/annotations-peer.jsonld" to jsonArrayOf(w3c("ann-item1", 100L, "peer-device")),
                "$ITEM_2/annotations-peer.jsonld" to jsonArrayOf(w3c("ann-item2", 200L, "peer-device")),
            ),
        )
        val sweep = newSweep(target, sources = listOf(source(SRV, NS)))

        sweep.run()

        val byItem = dao.upserts.groupBy { it.itemId }
        val item1Ids = byItem[ITEM_1]?.map { it.id } ?: emptyList()
        val item2Ids = byItem[ITEM_2]?.map { it.id } ?: emptyList()
        assertTrue(item1Ids.any { it.contains("ann-item1") }, "item-1 must have its own annotations merged")
        assertTrue(item2Ids.any { it.contains("ann-item2") }, "item-2 must have its own annotations merged")
    }

    // ===== own device file is skipped =====

    @Test
    fun ownDeviceFileIsSkippedNoPullForOwnDevice() = runTest {
        val target = PullRecordingTarget(
            deviceListing = NamespaceDeviceListing(
                listOf(
                    DeviceFileSummary(
                        deviceId = OWN_DEVICE_ID,
                        annotationFiles = listOf(AnnotationFileRef(ITEM_1, "annotations-$OWN_DEVICE_ID.jsonld")),
                    ),
                ),
            ),
            fileContent = mapOf("annotations-$OWN_DEVICE_ID.jsonld" to jsonArrayOf(w3c("own-ann", 100L, OWN_DEVICE_ID))),
        )
        val sweep = newSweep(target, sources = listOf(source(SRV, NS)))

        sweep.run()

        // Own device's file is already in Room; no merge should be triggered.
        assertTrue(dao.upserts.isEmpty(), "own-device annotations must not be re-merged by pull sweep")
    }

    // ===== multiple sources =====

    @Test
    fun multipleSourcesAreEachPulled() = runTest {
        val target = PullRecordingTarget(
            deviceListing = NamespaceDeviceListing(
                listOf(
                    DeviceFileSummary(
                        deviceId = "peer",
                        annotationFiles = listOf(AnnotationFileRef(ITEM_1, "annotations-peer.jsonld")),
                    ),
                ),
            ),
            fileContent = mapOf("annotations-peer.jsonld" to jsonArrayOf(w3c("ann-X", 50L, "peer"))),
        )
        val sources = listOf(source("srv-A", "ns-A"), source("srv-B", "ns-B"))
        val sweep = newSweep(target, sources = sources)

        sweep.run()

        assertEquals(2, target.enumerateCalls, "enumerateDevices must be called once per source")
    }

    // ===== source without configured namespace is skipped =====

    @Test
    fun sourceWithNoConfiguredNamespaceIsSkipped() = runTest {
        val target = PullRecordingTarget(deviceListing = NamespaceDeviceListing(emptyList()))
        val sources = listOf(
            Source(
                id = "srv-no-ns",
                url = SourceUrl.parse("https://example.test/")!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "testuser",
            ),
        )
        val sweep = AnnotationPullSweep(
            targetProvider = { target },
            syncController = newController(target),
            sourceRepository = PullFakeSourceRepository(sources, namespaces = emptyMap()),
            statusStore = statusStore,
            clock = { NOW },
        )

        val outcome = sweep.run()

        assertTrue(outcome is CycleOutcome.Success)
        assertEquals(0, target.enumerateCalls, "source without namespace must not trigger enumerateDevices")
    }

    // ===== enumerateDevices error =====

    @Test
    fun enumerateDevicesFailureReportsFailedCycleOutcome() = runTest {
        val target = PullRecordingTarget(
            deviceListing = null,
            enumerateException = RuntimeException("PROPFIND failed"),
        )
        val sweep = newSweep(target, sources = listOf(source(SRV, NS)))

        val outcome = sweep.run()

        assertTrue(outcome is CycleOutcome.Failed)
    }

    // ===== helpers =====

    private fun jsonArrayOf(vararg jsonObjects: String): String =
        "[\n" + jsonObjects.joinToString(",\n") + "\n]"

    private fun w3c(id: String, updatedAt: Long, deviceId: String): String =
        AnnotationW3CCodec.annotationEntityToW3C(
            AnnotationEntity(
                id = id,
                sourceId = SRV,
                itemId = ITEM_1,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)",
                color = "yellow",
                note = null,
                textSnippet = "x",
                textBefore = "",
                textAfter = "",
                chapterHref = "c1",
                createdAt = updatedAt,
                updatedAt = updatedAt,
                originDeviceId = deviceId,
                lastModifiedByDeviceId = deviceId,
            ),
        )

    private fun source(id: String, namespace: String) = Source(
        id = id,
        url = SourceUrl.parse("https://example.test/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "testuser",
        absUserId = namespace,
    )

    companion object {
        const val SRV = "srv-1"
        const val NS = "abs-user-uuid"
        const val ITEM_1 = "item-1"
        const val ITEM_2 = "item-2"
        const val OWN_DEVICE_ID = "own-device-uuid"
        const val NOW = 10_000L
    }

    private class PullRecordingTarget(
        private val deviceListing: NamespaceDeviceListing?,
        private val fileContent: Map<String, String> = emptyMap(),
        private val enumerateException: Throwable? = null,
    ) : AnnotationSyncTarget {
        var enumerateCalls = 0
        override suspend fun list(namespace: String, itemId: String) = fileContent.keys.toList()
        override suspend fun read(namespace: String, itemId: String, filename: String) =
            fileContent["$itemId/$filename"] ?: fileContent[filename]
        override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {}
        override suspend fun delete(namespace: String, itemId: String, filename: String) {}
        override suspend fun readDeviceMeta(namespace: String, deviceId: String) = null
        override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {}
        override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {}
        override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
            enumerateCalls++
            enumerateException?.let { throw it }
            return deviceListing!!
        }
        override suspend fun enumerateNamespaces(): List<NamespaceSummary> = emptyList()
        override suspend fun forgetNamespace(namespace: String): Int = 0
    }

    private class PullTestAnnotationDao : StubAnnotationDao() {
        val upserts: MutableList<AnnotationEntity> = mutableListOf()
        override suspend fun upsertAll(annotations: List<AnnotationEntity>) {
            upserts += annotations
        }
        override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String) =
            upserts.filter { it.sourceId == sourceId && it.itemId == itemId }
    }

    private class PullFakeDeviceIdStore(private val id: String) : DeviceIdStore {
        override suspend fun getOrCreate() = id
    }

    private object PullStubLabelResolver : DeviceLabelResolver {
        override suspend fun resolveLabel(deviceId: String) = "test-label"
        override fun deviceModel() = "test-model"
    }

    private class PullFakeSourceRepository(
        private val sources: List<Source>,
        private val namespaces: Map<String, String> = sources.associate { s -> s.id to (s.absUserId ?: s.id) },
    ) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(sources)
        override suspend fun getActive(): Source? = sources.firstOrNull { it.isActive }
        override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace =
            namespaces[sourceId]?.let { SyncNamespace.Configured(it) }
                ?: SyncNamespace.LocalOnly("no namespace")
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            error("not used")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}
