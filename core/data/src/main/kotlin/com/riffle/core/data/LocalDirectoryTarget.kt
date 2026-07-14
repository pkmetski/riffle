package com.riffle.core.data

import android.content.Context
import android.util.Log
import com.riffle.core.domain.AnnotationFileRef
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import java.io.File

/**
 * File-based implementation of [AnnotationSyncTarget] using app-controlled storage.
 *
 * Stores per-device annotation files in the app's internal files directory:
 * ```
 * <filesDir>/annotation-sync/<namespace>/<itemId>/annotations-<deviceId>.jsonld
 * <filesDir>/annotation-sync/<namespace>/device-meta-<deviceId>.json   (sentinel; no itemId)
 * ```
 *
 * This is a test scaffold for issue #75. Issue #76 adds the first network backend
 * (WebDAV) behind the same interface; the local-directory target remains useful for
 * tests and offline-only configurations.
 */
class LocalDirectoryTarget(private val context: Context) : AnnotationSyncTarget {

    @Volatile private var legacyAbsMigrated: Boolean = false

    /**
     * Rename any bare-UUID namespace directories under [ROOT] to their `abs_<uuid>` form so
     * pre-`abs_` local files stay addressable after the descriptor started stamping the new
     * prefix. Runs at most once per instance — the first read/write/enumerate triggers it,
     * every subsequent call short-circuits on [legacyAbsMigrated]. Idempotent when a fresh
     * install has no legacy dirs (zero listFiles work).
     */
    private fun migrateLegacyAbsDirs() {
        if (legacyAbsMigrated) return
        legacyAbsMigrated = true
        try {
            val root = File(context.filesDir, ROOT)
            if (!root.exists()) return
            root.listFiles { f -> f.isDirectory }?.forEach { nsDir ->
                if (!LegacyAbsNamespaceMigration.isLegacyAbsNamespaceSegment(nsDir.name)) return@forEach
                val renamed = File(nsDir.parentFile, com.riffle.core.domain.AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX + nsDir.name)
                if (renamed.exists()) return@forEach // another migration path already handled it
                nsDir.renameTo(renamed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy ABS dir migration failed (best-effort)", e)
        }
    }

    override suspend fun list(namespace: String, itemId: String): List<String> {
        migrateLegacyAbsDirs()
        return try {
            val directory = bookDir(namespace, itemId)
            if (!directory.exists()) return emptyList()
            directory.listFiles { file ->
                file.isFile && file.name.startsWith(ANNOTATION_NAME_PREFIX) && file.name.endsWith(JSONLD_SUFFIX)
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list annotations for $namespace/$itemId", e)
            emptyList()
        }
    }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? {
        migrateLegacyAbsDirs()
        return try {
            val file = annotationFile(namespace, itemId, filename)
            if (!file.exists()) return null
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $filename for $namespace/$itemId", e)
            throw Exception("Failed to read $filename: ${e.message}", e)
        }
    }

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        migrateLegacyAbsDirs()
        try {
            val directory = bookDir(namespace, itemId)
            if (!directory.exists() && !directory.mkdirs()) {
                throw Exception("Failed to create directory: ${directory.absolutePath}")
            }
            annotationFile(namespace, itemId, filename).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $filename for $namespace/$itemId", e)
            throw Exception("Failed to write $filename: ${e.message}", e)
        }
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        try {
            val file = annotationFile(namespace, itemId, filename)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete $filename for $namespace/$itemId", e)
        }
    }

    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? {
        migrateLegacyAbsDirs()
        return try {
            val file = deviceMetaFile(namespace, deviceId)
            if (!file.exists()) null else file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read device-meta $deviceId for $namespace", e)
            null
        }
    }

    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        migrateLegacyAbsDirs()
        try {
            val dir = namespaceDir(namespace)
            if (!dir.exists() && !dir.mkdirs()) {
                throw Exception("Failed to create directory: ${dir.absolutePath}")
            }
            deviceMetaFile(namespace, deviceId).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write device-meta $deviceId for $namespace", e)
            throw Exception("Failed to write device-meta $deviceId: ${e.message}", e)
        }
    }

    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {
        try {
            val file = deviceMetaFile(namespace, deviceId)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete device-meta $deviceId for $namespace", e)
        }
    }

    override suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing {
        migrateLegacyAbsDirs()
        return try {
            val nsDir = namespaceDir(namespace)
            if (!nsDir.exists()) return NamespaceDeviceListing(emptyList())

            val annotationFiles = mutableMapOf<String, MutableList<AnnotationFileRef>>()

            // Annotation files live under <namespace>/<itemId>/.
            nsDir.listFiles { f -> f.isDirectory }?.forEach { itemDir ->
                val itemId = itemDir.name
                itemDir.listFiles { f ->
                    f.isFile && f.name.startsWith(ANNOTATION_NAME_PREFIX) && f.name.endsWith(JSONLD_SUFFIX)
                }?.forEach { f ->
                    val deviceId = f.name.removePrefix(ANNOTATION_NAME_PREFIX).removeSuffix(JSONLD_SUFFIX)
                    if (deviceId.isEmpty()) return@forEach
                    annotationFiles
                        .getOrPut(deviceId) { mutableListOf() }
                        .add(AnnotationFileRef(itemId = itemId, filename = f.name))
                }
            }

            val rows = annotationFiles.keys.toSortedSet().map { deviceId ->
                DeviceFileSummary(
                    deviceId = deviceId,
                    annotationFiles = annotationFiles[deviceId]?.toList().orEmpty(),
                )
            }
            NamespaceDeviceListing(devices = rows)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate devices for $namespace", e)
            NamespaceDeviceListing(emptyList())
        }
    }

    override suspend fun enumerateNamespaces(): List<NamespaceSummary> {
        migrateLegacyAbsDirs()
        return try {
            val root = File(context.filesDir, ROOT)
            if (!root.exists()) return emptyList()
            root.listFiles { f -> f.isDirectory }?.map { nsDir ->
                var annotations = 0
                nsDir.listFiles { f -> f.isDirectory }?.forEach { itemDir ->
                    itemDir.listFiles { f ->
                        f.isFile && f.name.startsWith(ANNOTATION_NAME_PREFIX) && f.name.endsWith(JSONLD_SUFFIX)
                    }?.let { annotations += it.size }
                }
                NamespaceSummary(
                    namespace = nsDir.name,
                    annotationFileCount = annotations,
                )
            }.orEmpty().sortedBy { it.namespace }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate namespaces", e)
            emptyList()
        }
    }

    override suspend fun forgetNamespace(namespace: String): Int {
        migrateLegacyAbsDirs()
        return try {
            val dir = namespaceDir(namespace)
            if (!dir.exists()) return 0
            var deleted = 0
            dir.walkBottomUp().forEach { f ->
                if (f == dir) return@forEach
                if (f.isFile) {
                    if (f.delete()) deleted++
                } else {
                    f.delete()
                }
            }
            dir.delete()
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forget namespace $namespace", e)
            0
        }
    }

    private fun namespaceDir(namespace: String): File =
        File(context.filesDir, "$ROOT/$namespace")

    private fun bookDir(namespace: String, itemId: String): File =
        File(namespaceDir(namespace), itemId)

    private fun annotationFile(namespace: String, itemId: String, filename: String): File =
        File(bookDir(namespace, itemId), filename)

    private fun deviceMetaFile(namespace: String, deviceId: String): File =
        File(namespaceDir(namespace), "$DEVICE_META_NAME_PREFIX$deviceId$JSON_SUFFIX")

    companion object {
        private const val TAG = "LocalDirectoryTarget"
        private const val ROOT = "annotation-sync"
        private const val ANNOTATION_NAME_PREFIX = "annotations-"
        private const val DEVICE_META_NAME_PREFIX = "device-meta-"
        private const val JSONLD_SUFFIX = ".jsonld"
        private const val JSON_SUFFIX = ".json"
    }
}
