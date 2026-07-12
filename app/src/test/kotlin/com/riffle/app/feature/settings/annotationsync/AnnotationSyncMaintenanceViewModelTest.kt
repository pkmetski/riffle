package com.riffle.app.feature.settings.annotationsync

import com.riffle.core.data.AnnotationDeviceMetaCodec
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.domain.AnnotationDeviceMeta
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VM-level coverage for the foreign-user grouping introduced when Maintenance moved from
 * "Other namespaces" to "Other users". The data layer is covered separately in
 * AnnotationSyncMaintenanceTest / AnnotationFileHeaderCodecTest; this file pins down the
 * VM-only behaviour: hydrating each foreign namespace into a group with a per-row namespace,
 * picking up the writing account's username from the file headers, and routing a per-device
 * Forget under a foreign group to the foreign namespace (not the active one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncMaintenanceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `this-device row reads Last synced from the in-memory status store, not from any file`() = runTest {
        // The status store advances on every successful cycle (including pull-only), so this is
        // the only honest source for the current device's "Last synced". The per-device sentinel
        // on disk lags the cycle store by a network write, and per-file headers don't capture
        // pull-only cycles at all.
        val activeNs = "alice-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
            ),
        )
        val statusStore = AnnotationSyncStatusStore().apply {
            report(CycleOutcome.Success(atMs = 1_780_000_000_000L))
        }

        val vm = vmWith(target, activeNs = activeNs, statusStore = statusStore)
        advanceUntilIdle()

        val rows = (vm.state.value.devices as MaintenanceScreenUiState.Loaded).devices
        val secondary = rows.single { it.isThisDevice }.secondary
        assertTrue("got: $secondary", secondary.contains("Last synced"))
        // No "Last seen" — the relabel and source change took effect together.
        assertTrue("got: $secondary", !secondary.contains("Last seen"))
    }

    @Test
    fun `this-device row keeps Last synced visible after a Success is followed by a Failed cycle`() = runTest {
        // Regression for the bug where Maintenance hid "Last synced" the moment we went offline:
        // it read lastCycleOutcome and pattern-matched Success, so a subsequent Failed.Network
        // erased the timestamp even though sync had succeeded an hour earlier.
        val activeNs = "alice-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
            ),
        )
        val statusStore = AnnotationSyncStatusStore().apply {
            report(CycleOutcome.Success(atMs = 1_780_000_000_000L))
            // A later transient failure must not erase the sticky last-success timestamp.
            report(CycleOutcome.Failed.Network(atMs = 1_780_000_060_000L, message = "offline"))
        }

        val vm = vmWith(target, activeNs = activeNs, statusStore = statusStore)
        advanceUntilIdle()

        val rows = (vm.state.value.devices as MaintenanceScreenUiState.Loaded).devices
        val secondary = rows.single { it.isThisDevice }.secondary
        assertTrue("got: $secondary", secondary.contains("Last synced"))
    }

    @Test
    fun `this-device row shows no Last synced when the status store is NeverRun`() = runTest {
        val activeNs = "alice-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
            ),
        )
        // Default status store is NeverRun — fresh process, no cycle has fired yet.
        val vm = vmWith(target, activeNs = activeNs, statusStore = AnnotationSyncStatusStore())
        advanceUntilIdle()

        val rows = (vm.state.value.devices as MaintenanceScreenUiState.Loaded).devices
        val secondary = rows.single { it.isThisDevice }.secondary
        assertTrue("got: $secondary", !secondary.contains("Last synced"))
    }

    @Test
    fun `foreign namespace surfaces as Other Users group labelled by username from sentinel`() = runTest {
        val activeNs = "alice-userid"
        val foreignNs = "bob-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-bobs-phone.jsonld") to "[]",
            ),
        )
        target.deviceMetaFiles[foreignNs to "bobs-phone"] = AnnotationDeviceMetaCodec.encode(
            AnnotationDeviceMeta(
                deviceId = "bobs-phone",
                label = "Bob's Pixel",
                lastSyncedAt = "2026-06-25T12:00:00Z",
                username = "bob",
            )
        )

        val vm = vmWith(target, activeNs = activeNs)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.otherUsers.size)
        val group = state.otherUsers.single()
        assertEquals(foreignNs, group.namespace)
        assertEquals("bob", group.displayLabel)
        assertEquals(1, group.devices.size)
        assertEquals(foreignNs, group.devices.single().namespace)
        assertEquals("Bob's Pixel", group.devices.single().label)
        assertTrue("foreign-user device must not carry the This-device chip",
            !group.devices.single().isThisDevice)
    }

    @Test
    fun `forget on a foreign-user device routes to the foreign namespace`() = runTest {
        val activeNs = "alice-userid"
        val foreignNs = "bob-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-bobs-phone.jsonld") to "[]",
            ),
        )

        val vm = vmWith(target, activeNs = activeNs)
        advanceUntilIdle()
        val foreignRow = vm.state.value.otherUsers.single().devices.single()

        vm.onForgetRequested(foreignRow)
        vm.onForgetConfirmed()
        advanceUntilIdle()

        // The bug we're guarding against: forgetting a foreign row used to fall back to the
        // active namespace, which silently no-op'd because the deviceId didn't exist there.
        // After this call, the foreign device's annotation file must be gone.
        assertNull(target.files[FileKey(foreignNs, "i2", "annotations-bobs-phone.jsonld")])
        // The active-namespace files are untouched.
        assertNotNull(target.files[FileKey(activeNs, "i1", "annotations-this-device.jsonld")])
    }

    @Test
    fun `foreign namespace with no sentinel falls back to a null displayLabel`() = runTest {
        val activeNs = "alice-userid"
        val foreignNs = "old-userid"
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-some-old-device.jsonld") to "[]",
            ),
        )

        val vm = vmWith(target, activeNs = activeNs)
        advanceUntilIdle()

        val group = vm.state.value.otherUsers.single()
        assertNull("no sentinel for any device in this namespace → group has no displayLabel",
            group.displayLabel)
    }

    // --- fakes ---

    private fun vmWith(
        target: InMemoryTarget,
        activeNs: String,
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
    ): AnnotationSyncMaintenanceViewModel {
        val maintenance = AnnotationSyncMaintenance(targetProvider = { target })
        return AnnotationSyncMaintenanceViewModel(
            configStore = FakeConfigStore(configured = true),
            maintenance = maintenance,
            deviceIdStore = FakeDeviceIdStore("this-device"),
            deviceLabelStore = FakeDeviceLabelStore(),
            deviceLabelResolver = FakeDeviceLabelResolver("This Device"),
            sourceRepository = FakeServerRepository(activeAbsUserId = activeNs),
            statusStore = statusStore,
        )
    }

    private data class FileKey(val namespace: String, val itemId: String, val filename: String) {
        val deviceId: String = filename.removePrefix("annotations-").removeSuffix(".jsonld")
    }

    private class InMemoryTarget(
        val files: MutableMap<FileKey, String>,
    ) : AnnotationSyncTarget {
        override suspend fun list(namespace: String, itemId: String): List<String> =
            files.keys.filter { it.namespace == namespace && it.itemId == itemId }.map { it.filename }
        override suspend fun read(namespace: String, itemId: String, filename: String): String? =
            files[FileKey(namespace, itemId, filename)]
        override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
            files[FileKey(namespace, itemId, filename)] = content
        }
        override suspend fun delete(namespace: String, itemId: String, filename: String) {
            files.remove(FileKey(namespace, itemId, filename))
        }
        val deviceMetaFiles: MutableMap<Pair<String, String>, String> = mutableMapOf()
        override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? =
            deviceMetaFiles[namespace to deviceId]
        override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
            deviceMetaFiles[namespace to deviceId] = content
        }
        override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {
            deviceMetaFiles.remove(namespace to deviceId)
        }
        override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
            val byDevice = files.keys
                .filter { it.namespace == namespace }
                .groupBy { it.deviceId }
            val rows = byDevice.keys.toSortedSet().map { deviceId ->
                DeviceFileSummary(
                    deviceId = deviceId,
                    annotationFiles = byDevice[deviceId].orEmpty().map { AnnotationFileRef(it.itemId, it.filename) },
                )
            }
            return NamespaceDeviceListing(rows)
        }
        override suspend fun enumerateNamespaces(): List<NamespaceSummary> {
            val byNs = files.keys.groupBy { it.namespace }.mapValues { it.value.size }
            return byNs.keys.toSortedSet().map { ns ->
                NamespaceSummary(namespace = ns, annotationFileCount = byNs[ns] ?: 0)
            }
        }
        override suspend fun forgetNamespace(namespace: String): Int {
            val keys = files.keys.filter { it.namespace == namespace }
            keys.forEach { files.remove(it) }
            return keys.size
        }
    }

    private class FakeConfigStore(configured: Boolean) : AnnotationSyncConfigStore {
        private val state = MutableStateFlow(
            if (configured) AnnotationSyncConfig("http://x/", "u", "p") else null
        )
        override fun observe(): StateFlow<AnnotationSyncConfig?> = state
        override suspend fun save(config: AnnotationSyncConfig) { state.value = config }
        override suspend fun clear() { state.value = null }
    }

    private class FakeDeviceIdStore(private val id: String) : DeviceIdStore {
        override suspend fun getOrCreate() = id
    }

    private class FakeDeviceLabelStore : DeviceLabelStore {
        private val state = MutableStateFlow<String?>(null)
        override fun observe(): Flow<String?> = state
        override suspend fun get(): String? = state.value
        override suspend fun set(label: String?) { state.value = label }
    }

    private class FakeDeviceLabelResolver(private val label: String) : DeviceLabelResolver {
        override suspend fun resolveLabel(deviceId: String) = label
        override fun deviceModel() = "test-model"
    }

    private class FakeServerRepository(private val activeAbsUserId: String) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = Source(
            id = "srv-active",
            url = SourceUrl.parse("http://example.test/")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = activeAbsUserId,
        )
        override suspend fun getById(sourceId: String): Source? = getActive()
        override suspend fun ensureAbsUserId(sourceId: String): String = activeAbsUserId
        override suspend fun authenticate(
            url: SourceUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: ServerType,
            sourceType: com.riffle.core.domain.SourceType,
        ): AuthenticateResult = error("not used")
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult = error("not used")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }
}
