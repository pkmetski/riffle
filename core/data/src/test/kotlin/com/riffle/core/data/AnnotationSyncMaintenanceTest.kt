package com.riffle.core.data

import com.riffle.core.models.AnnotationDeviceMeta
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSyncMaintenanceTest {

    private fun maintenanceFor(target: AnnotationSyncTarget): AnnotationSyncMaintenance =
        AnnotationSyncMaintenance(targetProvider = { target }, nowIso = { "2026-02-02T02:02:02Z" })

    @Test
    fun `forgetDevice deletes every annotation file for that device only`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "item-1", "annotations-dev-A.jsonld") to "[]",
                FileKey("ns", "item-2", "annotations-dev-A.jsonld") to "[]",
                FileKey("ns", "item-1", "annotations-dev-B.jsonld") to "[]",
            ),
        )
        val m = maintenanceFor(target)
        val result = m.forgetDevice("ns", "dev-A")

        assertEquals(2, result.deletedAnnotationFiles)
        assertEquals(0, result.failures)
        assertFalse(target.files.keys.any { it.deviceId == "dev-A" })
        assertTrue(target.files.containsKey(FileKey("ns", "item-1", "annotations-dev-B.jsonld")))
    }

    @Test
    fun `forgetDevice also deletes the device's metadata sentinel`() = runTest {
        // Without this, forgetting a peer would leave its sentinel orphan on the share — and the
        // peer's row would resurrect carrying the pre-forget label/lastSyncedAt the next time
        // that deviceId pushed even a single annotation file.
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "item-1", "annotations-dev-A.jsonld") to "[]",
                FileKey("ns", "item-1", "annotations-dev-B.jsonld") to "[]",
            ),
        )
        target.deviceMetaFiles["ns" to "dev-A"] = AnnotationDeviceMetaCodec.encode(
            AnnotationDeviceMeta("dev-A", "A's phone", "2026-06-01T00:00:00Z")
        )
        target.deviceMetaFiles["ns" to "dev-B"] = AnnotationDeviceMetaCodec.encode(
            AnnotationDeviceMeta("dev-B", "B's phone", "2026-06-01T00:00:00Z")
        )

        maintenanceFor(target).forgetDevice("ns", "dev-A")

        assertFalse(target.deviceMetaFiles.containsKey("ns" to "dev-A"))
        // Other devices' sentinels are untouched.
        assertTrue(target.deviceMetaFiles.containsKey("ns" to "dev-B"))
    }

    @Test
    fun `listDevices reads the per-device sentinel for label and lastSyncedAt`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to "[]",
                FileKey("ns", "i1", "annotations-B.jsonld") to "[]",
            ),
        )
        target.deviceMetaFiles["ns" to "A"] = AnnotationDeviceMetaCodec.encode(
            AnnotationDeviceMeta("A", "Phone A", "2026-01-01T00:00:00Z", username = "alice")
        )

        val rows = maintenanceFor(target).listDevices("ns")

        assertEquals(2, rows.size)
        val a = rows.first { it.deviceId == "A" }
        val b = rows.first { it.deviceId == "B" }
        assertNotNull(a.metadata)
        assertEquals("Phone A", a.metadata!!.label)
        assertEquals("2026-01-01T00:00:00Z", a.metadata!!.lastSyncedAt)
        assertEquals("alice", a.metadata!!.username)
        // No sentinel for B → no metadata. The Maintenance UI shows just the deviceId-derived
        // label, which is the honest signal that we have no recent evidence of this peer.
        assertNull(b.metadata)
    }

    @Test
    fun `listDevices does NOT fall back to per-file headers when sentinel is missing`() = runTest {
        // Old per-file header carrying device-scoped fields. Maintenance must NOT mine it for
        // label/lastSyncedAt — that would reintroduce the push-vs-pull dishonesty.
        val legacy = """
            [
              {"type":"riffle:FileHeader","deviceId":"A","label":"Old Per-File Label","lastSeenAt":"2024-01-01T00:00:00Z","username":"alice","bookTitle":"Dune"},
              {"id":"x"}
            ]
        """.trimIndent()
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(FileKey("ns", "i1", "annotations-A.jsonld") to legacy),
        )

        val rows = maintenanceFor(target).listDevices("ns")

        assertEquals(1, rows.size)
        assertNull(rows.single().metadata)
    }

    @Test
    fun `publishHeader writes exactly one sentinel, with the new label`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to "[]",
                FileKey("ns", "i2", "annotations-A.jsonld") to "[]",
            ),
        )
        target.deviceMetaFiles["ns" to "A"] = AnnotationDeviceMetaCodec.encode(
            AnnotationDeviceMeta("A", "Old Name", "2025-09-15T10:00:00Z")
        )

        val result = maintenanceFor(target).publishHeader("ns", "A", "New Name", username = "alice")

        assertEquals(1, result.rewrittenFiles)
        assertEquals(0, result.failures)
        // No annotation files were rewritten — rename is a one-PUT operation now.
        target.files.values.forEach { assertEquals("[]", it) }
        val updated = AnnotationDeviceMetaCodec.decode(target.deviceMetaFiles["ns" to "A"]!!)!!
        assertEquals("New Name", updated.label)
        assertEquals("alice", updated.username)
        // Rename does NOT bump the visible "Last synced" — preserves the prior sentinel value.
        assertEquals("2025-09-15T10:00:00Z", updated.lastSyncedAt)
    }

    @Test
    fun `publishHeader mints a fallback lastSyncedAt when no sentinel exists yet`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(FileKey("ns", "i1", "annotations-A.jsonld") to "[]"),
        )

        maintenanceFor(target).publishHeader("ns", "A", "New Name", username = "alice")

        val written = AnnotationDeviceMetaCodec.decode(target.deviceMetaFiles["ns" to "A"]!!)!!
        assertEquals("New Name", written.label)
        assertEquals("2026-02-02T02:02:02Z", written.lastSyncedAt)
    }
}

private data class FileKey(val namespace: String, val itemId: String, val filename: String) {
    val deviceId: String = filename.removePrefix("annotations-").removeSuffix(".jsonld")
}

private class InMemoryMaintenanceTarget(
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
        val annotations = files.keys.groupBy { it.namespace }.mapValues { it.value.size }
        return annotations.keys.toSortedSet().map { ns ->
            NamespaceSummary(
                namespace = ns,
                annotationFileCount = annotations[ns] ?: 0,
            )
        }
    }

    override suspend fun forgetNamespace(namespace: String): Int {
        val keys = files.keys.filter { it.namespace == namespace }
        keys.forEach { files.remove(it) }
        return keys.size
    }
}
