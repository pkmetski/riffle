package com.riffle.core.data.absbookmark

import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary

/**
 * Fan-out [AnnotationSyncTarget] that dual-writes to multiple children and unions their reads.
 *
 * Used during the ABS-bookmark transition: an ABS-namespaced book writes to both the WebDAV
 * target (legacy) and the ABS-bookmark target (new). Reads union both — the merge orchestrator
 * dedups by W3C annotation `id`. Komga-namespaced books skip the ABS-bookmark child via
 * per-child [Child.serves] predicates. See
 * `docs/superpowers/specs/2026-07-19-abs-bookmarks-annotation-sync-design.md`.
 *
 * **Failure policy — per-target isolation.** A write failure on one child does not roll back or
 * block other children; the dirty ledger + sweep retry each child independently. If every
 * eligible child fails, the composite rethrows the first failure so the caller (sync controller)
 * treats the write as failed and re-queues.
 */
class CompositeAnnotationSyncTarget(
    private val children: List<Child>,
) : AnnotationSyncTarget {

    /**
     * @param target the underlying [AnnotationSyncTarget].
     * @param serves predicate returning true iff `namespace` should be routed to [target]. WebDAV
     *     children typically return true for every namespace; ABS-bookmark children return true
     *     only for their own account's namespace.
     * @param label short human-readable name for diagnostics.
     */
    data class Child(
        val target: AnnotationSyncTarget,
        val serves: (namespace: String) -> Boolean,
        val label: String,
    )

    private fun eligible(namespace: String): List<Child> = children.filter { it.serves(namespace) }

    override suspend fun list(namespace: String, itemId: String): List<String> {
        val els = eligible(namespace)
        val union = LinkedHashSet<String>()
        for (child in els) {
            runCatching { child.target.list(namespace, itemId) }.getOrNull()?.let(union::addAll)
        }
        return union.toList()
    }

    /**
     * First non-null wins. Files are keyed by `annotations-<deviceId>.jsonld` — the same device's
     * file on two children should carry equivalent annotations (the composite writes to both).
     * If they've diverged transiently (one child hasn't yet caught up), the merge orchestrator
     * running on top of the composite still dedups by annotation `id` because it also unions
     * the local DB. Deterministic child order for repeatability.
     */
    override suspend fun read(namespace: String, itemId: String, filename: String): String? {
        for (child in eligible(namespace)) {
            runCatching { child.target.read(namespace, itemId, filename) }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        fanOutOrThrow(namespace, "write") { it.write(namespace, itemId, filename, content) }
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        fanOutOrThrow(namespace, "delete") { it.delete(namespace, itemId, filename) }
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? {
        for (child in eligible(namespace)) {
            runCatching { child.target.readDeviceMeta(namespace, deviceId) }.getOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        fanOutOrThrow(namespace, "writeDeviceMeta") { it.writeDeviceMeta(namespace, deviceId, content) }
    }

    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {
        fanOutOrThrow(namespace, "deleteDeviceMeta") { it.deleteDeviceMeta(namespace, deviceId) }
    }

    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
        val merged = LinkedHashMap<String, MutableSet<com.riffle.core.domain.AnnotationFileRef>>()
        for (child in eligible(namespace)) {
            val listing = runCatching { child.target.enumerateDevices(namespace) }.getOrNull() ?: continue
            for (row in listing.devices) {
                merged.getOrPut(row.deviceId) { linkedSetOf() }.addAll(row.annotationFiles)
            }
        }
        return NamespaceDeviceListing(
            devices = merged.entries.map { (id, files) ->
                DeviceFileSummary(deviceId = id, annotationFiles = files.toList())
            },
        )
    }

    override suspend fun enumerateNamespaces(): List<NamespaceSummary> {
        val counts = LinkedHashMap<String, Int>()
        for (child in children) {
            val list = runCatching { child.target.enumerateNamespaces() }.getOrNull() ?: continue
            for (ns in list) {
                counts.merge(ns.namespace, ns.annotationFileCount) { a, b -> a + b }
            }
        }
        return counts.entries.map { (ns, c) -> NamespaceSummary(namespace = ns, annotationFileCount = c) }
    }

    override suspend fun forgetNamespace(namespace: String): Int {
        var total = 0
        for (child in eligible(namespace)) {
            total += runCatching { child.target.forgetNamespace(namespace) }.getOrDefault(0)
        }
        return total
    }

    private suspend fun fanOutOrThrow(
        namespace: String,
        opName: String,
        block: suspend (AnnotationSyncTarget) -> Unit,
    ) {
        val els = eligible(namespace)
        if (els.isEmpty()) {
            error("CompositeAnnotationSyncTarget.$opName: no child serves namespace=$namespace")
        }
        val failures = mutableListOf<Throwable>()
        for (child in els) {
            try {
                block(child.target)
            } catch (t: Throwable) {
                failures.add(RuntimeException("child=${child.label}: ${t.message}", t))
            }
        }
        if (failures.size == els.size) {
            // Every eligible child failed — surface so the sync controller retries the whole op.
            throw failures.first()
        }
        // Partial success is acceptable: successful children are up-to-date; failed children stay
        // dirty and get retried by the sweep worker.
    }
}
