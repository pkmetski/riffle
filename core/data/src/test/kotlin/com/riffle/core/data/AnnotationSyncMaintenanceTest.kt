package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceMetadata
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            legacySidecars = mutableSetOf(),
        )
        val m = maintenanceFor(target)
        val result = m.forgetDevice("ns", "dev-A")

        assertEquals(2, result.deletedAnnotationFiles)
        assertFalse(result.deletedLegacySidecar)
        assertEquals(0, result.failures)
        assertFalse(target.files.keys.any { it.deviceId == "dev-A" })
        assertTrue(target.files.containsKey(FileKey("ns", "item-1", "annotations-dev-B.jsonld")))
    }

    @Test
    fun `forgetDevice also nukes a leftover legacy sidecar`() = runTest {
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "item-1", "annotations-dev-A.jsonld") to "[]",
            ),
            legacySidecars = mutableSetOf("dev-A"),
        )
        val m = maintenanceFor(target)
        val result = m.forgetDevice("ns", "dev-A")

        assertEquals(1, result.deletedAnnotationFiles)
        assertTrue(result.deletedLegacySidecar)
        assertFalse(target.legacySidecars.contains("dev-A"))
    }

    @Test
    fun `listDevices extracts the embedded metadata header from annotation files`() = runTest {
        val headerA = DeviceMetadataCodec.buildFileBody(
            DeviceMetadata("A", "Phone A", "2026-01-01T00:00:00Z"),
            annotationJsonStrings = emptyList(),
        )
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to headerA,
                FileKey("ns", "i1", "annotations-B.jsonld") to "[]",
            ),
            legacySidecars = mutableSetOf(),
        )
        val m = maintenanceFor(target)
        val rows = m.listDevices("ns")

        assertEquals(2, rows.size)
        val a = rows.first { it.deviceId == "A" }
        val b = rows.first { it.deviceId == "B" }
        assertEquals(1, a.annotationFileCount)
        assertNotNull(a.metadata)
        assertEquals("Phone A", a.metadata!!.label)
        assertEquals(1, b.annotationFileCount)
        assertEquals(null, b.metadata)
    }

    @Test
    fun `publishDeviceMetadata rewrites the header in every annotation file the device owns`() = runTest {
        val originalA = DeviceMetadataCodec.buildFileBody(
            DeviceMetadata("A", "Old Name", "2026-01-01T00:00:00Z"),
            annotationJsonStrings = listOf("""{"id":"x"}"""),
        )
        val originalA2 = DeviceMetadataCodec.buildFileBody(
            DeviceMetadata("A", "Old Name", "2026-01-01T00:00:00Z"),
            annotationJsonStrings = listOf("""{"id":"y"}"""),
        )
        val target = InMemoryMaintenanceTarget(
            files = mutableMapOf(
                FileKey("ns", "i1", "annotations-A.jsonld") to originalA,
                FileKey("ns", "i2", "annotations-A.jsonld") to originalA2,
            ),
            legacySidecars = mutableSetOf(),
        )
        val m = maintenanceFor(target)
        m.publishDeviceMetadata("ns", "A", "New Name")

        target.files.values.forEach { body ->
            val header = DeviceMetadataCodec.extractHeader(body)!!
            assertEquals("New Name", header.label)
            assertEquals("2026-02-02T02:02:02Z", header.lastSeenAt)
        }
        // Annotation records are preserved.
        assertTrue(target.files.values.any { it.contains("\"id\":\"x\"") })
        assertTrue(target.files.values.any { it.contains("\"id\":\"y\"") })
    }
}

private data class FileKey(val namespace: String, val itemId: String, val filename: String) {
    val deviceId: String = filename.removePrefix("annotations-").removeSuffix(".jsonld")
}

private class InMemoryMaintenanceTarget(
    val files: MutableMap<FileKey, String>,
    val legacySidecars: MutableSet<String>,
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
    override suspend fun deleteDeviceSidecar(namespace: String, deviceId: String) {
        legacySidecars.remove(deviceId)
    }
    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
        val byDevice = files.keys
            .filter { it.namespace == namespace }
            .groupBy { it.deviceId }
        val rows = byDevice.keys.toSortedSet().map { deviceId ->
            DeviceFileSummary(
                deviceId = deviceId,
                annotationFiles = byDevice[deviceId].orEmpty().map { AnnotationFileRef(it.itemId, it.filename) },
                hasLegacySidecar = legacySidecars.contains(deviceId),
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
                sidecarCount = if (ns == "ns") legacySidecars.size else 0,
            )
        }
    }

    override suspend fun forgetNamespace(namespace: String): Int {
        val keys = files.keys.filter { it.namespace == namespace }
        keys.forEach { files.remove(it) }
        var deleted = keys.size
        if (namespace == "ns") {
            deleted += legacySidecars.size
            legacySidecars.clear()
        }
        return deleted
    }
}
