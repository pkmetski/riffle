package com.riffle.core.data.absbookmark

import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeAnnotationSyncTargetTest {

    @Test
    fun `write fans out to every eligible child`() = runTest {
        val webdav = InMemTarget("webdav")
        val abs = InMemTarget("abs")
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        target.write("abs_test", "book-1", "annotations-devA.jsonld", "payload-1")
        assertEquals("payload-1", webdav.state["abs_test|book-1|annotations-devA.jsonld"])
        assertEquals("payload-1", abs.state["abs_test|book-1|annotations-devA.jsonld"])
    }

    @Test
    fun `komga namespace skips ABS child`() = runTest {
        val webdav = InMemTarget("webdav")
        val abs = InMemTarget("abs")
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        target.write("komga_x", "book-2", "annotations-devA.jsonld", "kv")
        assertEquals("kv", webdav.state["komga_x|book-2|annotations-devA.jsonld"])
        assertTrue("ABS should be untouched for komga namespace", abs.state.isEmpty())
    }

    @Test
    fun `read returns first non-null across children`() = runTest {
        val webdav = InMemTarget("webdav")
        val abs = InMemTarget("abs")
        webdav.state["abs_test|book-1|annotations-devA.jsonld"] = "from-webdav"
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        assertEquals(
            "from-webdav",
            target.read("abs_test", "book-1", "annotations-devA.jsonld"),
        )
        // With no data on either side returns null.
        assertNull(target.read("abs_test", "book-XX", "annotations-devA.jsonld"))
    }

    @Test
    fun `partial write failure does not throw when at least one child succeeds`() = runTest {
        val webdav = InMemTarget("webdav")
        val abs = InMemTarget("abs").also { it.failNextWrite = true }
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        target.write("abs_test", "book-1", "annotations-devA.jsonld", "p")
        assertEquals("p", webdav.state["abs_test|book-1|annotations-devA.jsonld"])
        assertTrue("ABS should have failed", abs.state.isEmpty())
    }

    @Test
    fun `write throws when every eligible child fails`() = runTest {
        val webdav = InMemTarget("webdav").also { it.failNextWrite = true }
        val abs = InMemTarget("abs").also { it.failNextWrite = true }
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking {
                target.write("abs_test", "book-1", "annotations-devA.jsonld", "p")
            }
        }
    }

    @Test
    fun `enumerateNamespaces sums per-namespace across children`() = runTest {
        val webdav = InMemTarget("webdav").also {
            it.namespaceCounts["abs_test"] = 3
            it.namespaceCounts["komga_x"] = 5
        }
        val abs = InMemTarget("abs").also {
            it.namespaceCounts["abs_test"] = 2
        }
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        val ns = target.enumerateNamespaces().associate { it.namespace to it.annotationFileCount }
        assertEquals(mapOf("abs_test" to 5, "komga_x" to 5), ns)
    }

    @Test
    fun `enumerateDevices unions files per deviceId across children`() = runTest {
        val webdav = InMemTarget("webdav").also {
            it.enumerateDevicesReturns["abs_test"] = NamespaceDeviceListing(
                devices = listOf(
                    DeviceFileSummary(
                        deviceId = "devA",
                        annotationFiles = listOf(AnnotationFileRef("book-1", "annotations-devA.jsonld")),
                    ),
                ),
            )
        }
        val abs = InMemTarget("abs").also {
            it.enumerateDevicesReturns["abs_test"] = NamespaceDeviceListing(
                devices = listOf(
                    DeviceFileSummary(
                        deviceId = "devA",
                        annotationFiles = listOf(AnnotationFileRef("book-2", "annotations-devA.jsonld")),
                    ),
                    DeviceFileSummary(
                        deviceId = "devB",
                        annotationFiles = listOf(AnnotationFileRef("book-3", "annotations-devB.jsonld")),
                    ),
                ),
            )
        }
        val target = CompositeAnnotationSyncTarget(
            listOf(
                CompositeAnnotationSyncTarget.Child(webdav, { true }, "webdav"),
                CompositeAnnotationSyncTarget.Child(abs, { it.startsWith("abs_") }, "abs"),
            ),
        )
        val listing = target.enumerateDevices("abs_test")
        val byId = listing.devices.associate { it.deviceId to it.annotationFiles.map { f -> f.itemId }.toSet() }
        assertEquals(setOf("book-1", "book-2"), byId["devA"])
        assertEquals(setOf("book-3"), byId["devB"])
    }
}

private class InMemTarget(val id: String) : AnnotationSyncTarget {
    val state = LinkedHashMap<String, String>()
    val namespaceCounts = LinkedHashMap<String, Int>()
    val enumerateDevicesReturns = LinkedHashMap<String, NamespaceDeviceListing>()
    var failNextWrite: Boolean = false

    private fun key(ns: String, itemId: String, filename: String) = "$ns|$itemId|$filename"

    override suspend fun list(namespace: String, itemId: String): List<String> =
        state.keys.filter { it.startsWith("$namespace|$itemId|") }.map { it.substringAfterLast('|') }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? =
        state[key(namespace, itemId, filename)]

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        if (failNextWrite) {
            failNextWrite = false
            throw RuntimeException("$id: injected write failure")
        }
        state[key(namespace, itemId, filename)] = content
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        state.remove(key(namespace, itemId, filename))
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? = null
    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {}
    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {}

    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing =
        enumerateDevicesReturns[namespace] ?: NamespaceDeviceListing(devices = emptyList())

    override suspend fun enumerateNamespaces(): List<NamespaceSummary> =
        namespaceCounts.map { (ns, c) -> NamespaceSummary(ns, c) }

    override suspend fun forgetNamespace(namespace: String): Int {
        val victims = state.keys.filter { it.startsWith("$namespace|") }
        victims.forEach { state.remove(it) }
        return victims.size
    }
}
