package com.riffle.app.feature.settings.annotationsync

import com.riffle.core.data.AnnotationFileHeaderCodec
import com.riffle.core.data.AnnotationSyncMaintenance
import com.riffle.core.domain.AnnotationFileHeader
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
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
    fun `foreign namespace shows up as Other Users group labelled by username from header`() = runTest {
        val activeNs = "alice-userid"
        val foreignNs = "bob-userid"
        val foreignHeader = AnnotationFileHeaderCodec.buildFileBody(
            AnnotationFileHeader(
                deviceId = "bobs-phone",
                label = "Bob's Pixel",
                lastSeenAt = "2026-06-25T12:00:00Z",
                username = "bob",
                bookTitle = "Project Hail Mary",
            ),
            annotationJsonStrings = emptyList(),
        )
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-bobs-phone.jsonld") to foreignHeader,
            ),
        )

        val vm = vmWith(target, activeNs = activeNs)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.otherUsers.size)
        val group = state.otherUsers.single()
        assertEquals(foreignNs, group.namespace)
        // Username from the header object surfaces as the group label — not the opaque user.id.
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
        val foreignHeader = AnnotationFileHeaderCodec.buildFileBody(
            AnnotationFileHeader("bobs-phone", "Bob's Pixel", "2026-06-25T12:00:00Z", "bob"),
            annotationJsonStrings = emptyList(),
        )
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-bobs-phone.jsonld") to foreignHeader,
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
    fun `legacy file with no username falls back to a null displayLabel`() = runTest {
        val activeNs = "alice-userid"
        val foreignNs = "old-userid"
        // Header-less file (pre-embed-header refactor) → listDevices yields a row with null metadata.
        val target = InMemoryTarget(
            files = mutableMapOf(
                FileKey(activeNs, "i1", "annotations-this-device.jsonld") to "[]",
                FileKey(foreignNs, "i2", "annotations-some-old-device.jsonld") to "[]",
            ),
        )

        val vm = vmWith(target, activeNs = activeNs)
        advanceUntilIdle()

        val group = vm.state.value.otherUsers.single()
        assertNull("no username in any header → group has no displayLabel", group.displayLabel)
    }

    // --- fakes ---

    private fun vmWith(
        target: InMemoryTarget,
        activeNs: String,
    ): AnnotationSyncMaintenanceViewModel {
        val maintenance = AnnotationSyncMaintenance(targetProvider = { target })
        return AnnotationSyncMaintenanceViewModel(
            configStore = FakeConfigStore(configured = true),
            maintenance = maintenance,
            deviceIdStore = FakeDeviceIdStore("this-device"),
            deviceLabelStore = FakeDeviceLabelStore(),
            deviceLabelResolver = FakeDeviceLabelResolver("This Device"),
            serverRepository = FakeServerRepository(activeAbsUserId = activeNs),
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
        override suspend fun deleteDeviceSidecar(namespace: String, deviceId: String) {}
        override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
            val byDevice = files.keys
                .filter { it.namespace == namespace }
                .groupBy { it.deviceId }
            val rows = byDevice.keys.toSortedSet().map { deviceId ->
                DeviceFileSummary(
                    deviceId = deviceId,
                    annotationFiles = byDevice[deviceId].orEmpty().map { AnnotationFileRef(it.itemId, it.filename) },
                    hasLegacySidecar = false,
                )
            }
            return NamespaceDeviceListing(rows)
        }
        override suspend fun enumerateNamespaces(): List<NamespaceSummary> {
            val byNs = files.keys.groupBy { it.namespace }.mapValues { it.value.size }
            return byNs.keys.toSortedSet().map { ns ->
                NamespaceSummary(namespace = ns, annotationFileCount = byNs[ns] ?: 0, sidecarCount = 0)
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

    private class FakeServerRepository(private val activeAbsUserId: String) : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = flowOf(emptyList())
        override suspend fun getActive(): Server? = Server(
            id = "srv-active",
            url = ServerUrl.parse("http://example.test/")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = activeAbsUserId,
        )
        override suspend fun getById(serverId: String): Server? = getActive()
        override suspend fun ensureAbsUserId(serverId: String): String = activeAbsUserId
        override suspend fun authenticate(
            url: ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: ServerType,
        ): AuthenticateResult = error("not used")
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult = error("not used")
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }
}
