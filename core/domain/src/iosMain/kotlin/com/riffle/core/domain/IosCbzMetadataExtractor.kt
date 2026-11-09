package com.riffle.core.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

data class IosCbzMetadata(
    val pageCount: Int,
    val coverBytes: ByteArray?,
    val coverExtension: String?,
) {
    companion object {
        val EMPTY = IosCbzMetadata(pageCount = 0, coverBytes = null, coverExtension = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
object IosCbzMetadataExtractor {

    private val IMAGE_EXTENSIONS = mapOf(
        "jpg" to "jpg", "jpeg" to "jpg",
        "png" to "png", "gif" to "gif",
        "webp" to "webp", "bmp" to "bmp",
    )

    fun extract(cbzPath: String): IosCbzMetadata = try {
        val data = NSData.dataWithContentsOfFile(cbzPath) ?: return IosCbzMetadata.EMPTY
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isEmpty()) return IosCbzMetadata.EMPTY
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        extract(bytes)
    } catch (_: Exception) {
        IosCbzMetadata.EMPTY
    }

    fun extract(cbzBytes: ByteArray): IosCbzMetadata = try {
        val archive = IosZipArchive(cbzBytes)
        val imageEntries = archive.entryNames()
            .filter { name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                IMAGE_EXTENSIONS.containsKey(ext) && !name.substringAfterLast('/').startsWith(".")
            }
            .sorted()
        if (imageEntries.isEmpty()) return IosCbzMetadata.EMPTY
        val coverName = imageEntries.first()
        val coverExt = coverName.substringAfterLast('.', "").lowercase()
        val coverBytes = archive.readEntry(coverName)
        IosCbzMetadata(
            pageCount = imageEntries.size,
            coverBytes = coverBytes,
            coverExtension = IMAGE_EXTENSIONS[coverExt],
        )
    } catch (_: Exception) {
        IosCbzMetadata.EMPTY
    }
}
