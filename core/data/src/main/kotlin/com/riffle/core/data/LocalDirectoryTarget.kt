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
 * This is a test scaffold for issue #75 and will be replaced by a Google Drive
 * implementation in issue #77.
 */
class LocalDirectoryTarget(private val context: Context) : AnnotationSyncTarget {

    override suspend fun list(serverId: String, itemId: String): List<String> {
        return try {
            val directory = getBookDirectory(serverId, itemId)
            if (!directory.exists()) {
                return emptyList()
            }
            directory.listFiles { file ->
                file.isFile && file.name.endsWith(".jsonld")
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list annotations for $serverId/$itemId", e)
            emptyList()
        }
    }

    override suspend fun read(serverId: String, itemId: String, filename: String): String? {
        return try {
            val file = getFile(serverId, itemId, filename)
            if (!file.exists()) {
                return null
            }
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file $filename for $serverId/$itemId", e)
            throw Exception("Failed to read $filename: ${e.message}", e)
        }
    }

    override suspend fun write(serverId: String, itemId: String, filename: String, content: String) {
        try {
            val directory = getBookDirectory(serverId, itemId)
            if (!directory.exists()) {
                if (!directory.mkdirs()) {
                    throw Exception("Failed to create directory: ${directory.absolutePath}")
                }
            }
            val file = getFile(serverId, itemId, filename)
            file.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file $filename for $serverId/$itemId", e)
            throw Exception("Failed to write $filename: ${e.message}", e)
        }
    }

    private fun getBookDirectory(serverId: String, itemId: String): File {
        return File(context.filesDir, "annotation-sync/$serverId/$itemId")
    }

    private fun getFile(serverId: String, itemId: String, filename: String): File {
        return File(getBookDirectory(serverId, itemId), filename)
    }

    companion object {
        private const val TAG = "LocalDirectoryTarget"
    }
}
