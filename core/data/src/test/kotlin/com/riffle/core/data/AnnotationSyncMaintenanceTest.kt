package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSyncMaintenanceTest {

    private fun maintenanceFor(target: AnnotationSyncTarget): AnnotationSyncMaintenance =
        AnnotationSyncMaintenance(targetProvider = { target })

    @Test
    fun `forgetDevice deletes every annotation file plus the sidecar for that device only`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "item-1", "annotations-dev-A.jsonld") to "[]",
                FileKey("ns", "item-2", "annotations-dev-A.jsonld") to "[]",
                FileKey("ns", "item-1", "annotations-dev-B.jsonld") to "[]",
            ),
            sidecars = mutableMapOf("dev-A" to "{}", "dev-B" to "{}"),
        )
        val m = maintenanceFor(target)
        val result = m.forgetDevice("ns", "dev-A")

        assertEquals(2, result.deletedAnnotationFiles)
        assertTrue(result.deletedSidecar)
        assertEquals(0, result.failures)

        // dev-A's files are gone, dev-B's are intact
        assertFalse(target.files.keys.any { it.deviceId == "dev-A" })
        assertTrue(target.files.containsKey(FileKey("ns", "item-1", "annotations-dev-B.jsonld")))
        assertFalse(target.sidecars.containsKey("dev-A"))
        assertTrue(target.sidecars.containsKey("dev-B"))
    }

    @Test
    fun `compactTombstones rewrites only files that contained tombstones`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to """[{"id":"a"},{"id":"b","riffle:deleted":"true"}]""",
                FileKey("ns", "i2", "annotations-A.jsonld") to """[{"id":"c"}]""",
            ),
            sidecars = mutableMapOf(),
        )
        val m = maintenanceFor(target)
        val result = m.compactTombstones("ns")

        assertEquals(2, result.filesScanned)
        assertEquals(1, result.filesRewritten)
        assertEquals(1, result.tombstonesRemoved)
        assertEquals(0, result.failures)

        // Rewritten file lost the tombstone; the clean file's content is untouched.
        val rewritten = target.files[FileKey("ns", "i1", "annotations-A.jsonld")]!!
        assertFalse(rewritten.contains("\"id\":\"b\""))
        assertTrue(rewritten.contains("\"id\":\"a\""))
        assertEquals("""[{"id":"c"}]""", target.files[FileKey("ns", "i2", "annotations-A.jsonld")])
    }

    @Test
    fun `listDevices hydrates sidecars when present and omits them when absent`() = runTest {
        val sidecarA = DeviceSidecarCodec.encode(
            com.riffle.core.domain.DeviceSidecar("A", "Phone A", "Pixel", "2026-01-01T00:00:00Z"),
        )
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to "[]",
                FileKey("ns", "i1", "annotations-B.jsonld") to "[]",
            ),
            sidecars = mutableMapOf("A" to sidecarA),
        )
        val m = maintenanceFor(target)
        val rows = m.listDevices("ns")

        assertEquals(2, rows.size)
        val a = rows.first { it.deviceId == "A" }
        val b = rows.first { it.deviceId == "B" }
        assertEquals(1, a.annotationFileCount)
        assertNotNull(a.sidecar)
        assertEquals("Phone A", a.sidecar!!.label)
        assertEquals(1, b.annotationFileCount)
        assertEquals(null, b.sidecar)
    }
}

private data class FileKey(val namespace: String, val itemId: String, val filename: String) {
    val deviceId: String = filename.removePrefix("annotations-").removeSuffix(".jsonld")
}

private class InMemoryMaintenanceTarget(
    val files: MutableMap<FileKey, String>,
    val sidecars: MutableMap<String, String>,
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
    override suspend fun readDeviceSidecar(namespace: String, deviceId: String): String? = sidecars[deviceId]
    override suspend fun writeDeviceSidecar(namespace: String, deviceId: String, content: String) {
        sidecars[deviceId] = content
    }
    override suspend fun deleteDeviceSidecar(namespace: String, deviceId: String) {
        sidecars.remove(deviceId)
    }
    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
        val byDevice = files.keys
            .filter { it.namespace == namespace }
            .groupBy { it.deviceId }
        val deviceIds = (byDevice.keys + sidecars.keys).toSortedSet()
        val rows = deviceIds.map { deviceId ->
            DeviceFileSummary(
                deviceId = deviceId,
                annotationFiles = byDevice[deviceId].orEmpty().map { AnnotationFileRef(it.itemId, it.filename) },
                hasSidecar = sidecars.containsKey(deviceId),
            )
        }
        return NamespaceDeviceListing(rows)
    }
}

