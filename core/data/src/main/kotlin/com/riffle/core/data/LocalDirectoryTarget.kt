package com.riffle.core.data

import android.content.Context
import android.util.Log
import com.riffle.core.domain.AnnotationSyncTarget
import java.io.File

/**
 * File-based implementation of [AnnotationSyncTarget] using app-controlled storage.
 *
 * Stores per-device annotation files in the app's internal files directory:
 * ```
 * <filesDir>/annotation-sync/<serverId>/<itemId>/annotations-<deviceId>.jsonld
 * ```
 *
 * This is a test scaffold for issue #75. Issue #76 adds the first network backend
 * (WebDAV) behind the same interface; the local-directory target remains useful for
 * tests and offline-only configurations.
 */
class LocalDirectoryTarget(private val context: Context) : AnnotationSyncTarget {

    override suspend fun list(namespace: String, itemId: String): List<String> {
        return try {
            val directory = getBookDirectory(namespace, itemId)
            if (!directory.exists()) {
                return emptyList()
            }
            directory.listFiles { file ->
                file.isFile && file.name.endsWith(".jsonld")
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list annotations for $namespace/$itemId", e)
            emptyList()
        }
    }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? {
        return try {
            val file = getFile(namespace, itemId, filename)
            if (!file.exists()) {
                return null
            }
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file $filename for $namespace/$itemId", e)
            throw Exception("Failed to read $filename: ${e.message}", e)
        }
    }

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        try {
            val directory = getBookDirectory(namespace, itemId)
            if (!directory.exists()) {
                if (!directory.mkdirs()) {
                    throw Exception("Failed to create directory: ${directory.absolutePath}")
                }
            }
            val file = getFile(namespace, itemId, filename)
            file.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file $filename for $namespace/$itemId", e)
            throw Exception("Failed to write $filename: ${e.message}", e)
        }
    }

    private fun getBookDirectory(namespace: String, itemId: String): File {
        return File(context.filesDir, "annotation-sync/$namespace/$itemId")
    }

    private fun getFile(namespace: String, itemId: String, filename: String): File {
        return File(getBookDirectory(namespace, itemId), filename)
    }

    companion object {
        private const val TAG = "LocalDirectoryTarget"
    }
}
