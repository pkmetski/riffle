package com.riffle.shared.reader

import com.riffle.core.domain.comic.ComicImageSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * In-memory CBZ (ZIP-of-images) reader. Parses the ZIP central directory from [archiveBytes]
 * and provides random-access to page images. Supports STORED (method 0) and DEFLATED (method 8).
 *
 * Entry order matches [CbzArchive] on Android: case-insensitive filename sort, image files only.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCbzArchive(private val archiveBytes: ByteArray) : ComicImageSource {

    private data class Entry(
        val name: String,
        val mediaType: String,
        val localHeaderOffset: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val compressionMethod: Int,
    )

    private val entries: List<Entry>

    init {
        entries = parseCentralDirectory()
            .filter { !it.name.contains("__MACOSX", ignoreCase = false) }
            .filter { !it.name.substringAfterLast('/').startsWith(".") }
            .mapNotNull { e ->
                val ext = e.name.substringAfterLast('.', "").lowercase()
                val media = IMAGE_EXTENSIONS[ext] ?: return@mapNotNull null
                e.copy(mediaType = media)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override val pageCount: Int get() = entries.size

    override fun mediaType(pageIndex: Int): String = entries[pageIndex].mediaType

    override fun imageBytes(pageIndex: Int): ByteArray {
        val entry = entries[pageIndex]
        val dataOffset = localFileDataOffset(entry.localHeaderOffset)
        return if (entry.compressionMethod == COMPRESSION_STORED) {
            archiveBytes.copyOfRange(dataOffset, dataOffset + entry.compressedSize)
        } else {
            inflate(archiveBytes, dataOffset, entry.compressedSize, entry.uncompressedSize)
        }
    }

    // region ZIP parsing

    private fun parseCentralDirectory(): List<Entry> {
        val eocdOffset = findEocdOffset() ?: return emptyList()
        val cdOffset = readInt32LE(eocdOffset + 16)
        val cdSize = readInt32LE(eocdOffset + 12)
        val entries = mutableListOf<Entry>()
        var pos = cdOffset
        while (pos < cdOffset + cdSize) {
            val sig = readInt32LE(pos)
            if (sig != 0x02014B50) break
            val method = readInt16LE(pos + 10)
            val compressedSize = readInt32LE(pos + 20)
            val uncompressedSize = readInt32LE(pos + 24)
            val nameLen = readInt16LE(pos + 28)
            val extraLen = readInt16LE(pos + 30)
            val commentLen = readInt16LE(pos + 32)
            val localHeaderOffset = readInt32LE(pos + 42)
            val name = archiveBytes.decodeToString(pos + 46, pos + 46 + nameLen)
            entries += Entry(
                name = name,
                mediaType = "",
                localHeaderOffset = localHeaderOffset,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                compressionMethod = method,
            )
            pos += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    private fun findEocdOffset(): Int? {
        // EOCD is at most 65535 + 22 bytes from the end; scan backward for 0x06054B50
        val minOffset = maxOf(0, archiveBytes.size - 65535 - 22)
        for (i in archiveBytes.size - 22 downTo minOffset) {
            if (readInt32LE(i) == 0x06054B50) return i
        }
        return null
    }

    private fun localFileDataOffset(localHeaderOffset: Int): Int {
        val nameLen = readInt16LE(localHeaderOffset + 26)
        val extraLen = readInt16LE(localHeaderOffset + 28)
        return localHeaderOffset + 30 + nameLen + extraLen
    }

    private fun readInt32LE(offset: Int): Int {
        return (archiveBytes[offset].toInt() and 0xFF) or
            ((archiveBytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((archiveBytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((archiveBytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readInt16LE(offset: Int): Int {
        return (archiveBytes[offset].toInt() and 0xFF) or
            ((archiveBytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    // endregion

    // region DEFLATE decompression via platform.zlib

    private fun inflate(
        src: ByteArray,
        srcOffset: Int,
        compressedSize: Int,
        uncompressedSize: Int,
    ): ByteArray {
        val output = ByteArray(uncompressedSize)
        val zs = nativeHeap.alloc<z_stream>()
        try {
            src.usePinned { srcPin ->
                output.usePinned { outPin ->
                    zs.next_in = srcPin.addressOf(srcOffset).reinterpret()
                    zs.avail_in = compressedSize.toUInt()
                    zs.next_out = outPin.addressOf(0).reinterpret()
                    zs.avail_out = uncompressedSize.toUInt()

                    val initResult = inflateInit2(zs.ptr, -15) // raw DEFLATE
                    check(initResult == Z_OK) { "inflateInit2 failed: $initResult" }

                    val inflateResult = inflate(zs.ptr, Z_NO_FLUSH)
                    check(inflateResult == Z_OK || inflateResult == Z_STREAM_END) {
                        "inflate failed: $inflateResult"
                    }
                }
            }
        } finally {
            inflateEnd(zs.ptr)
            nativeHeap.free(zs)
        }
        return output
    }

    // endregion

    companion object {
        private const val COMPRESSION_STORED = 0

        private val IMAGE_EXTENSIONS = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
        )
    }
}
