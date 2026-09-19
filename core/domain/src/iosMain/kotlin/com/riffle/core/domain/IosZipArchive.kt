package com.riffle.core.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
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
 * Random-access-by-path ZIP reader for iOS, used by [IosEpubCfiTranslator] to fetch
 * META-INF/container.xml, the OPF, and individual chapter HTML entries by exact path (unlike
 * [com.riffle.shared.reader.IosCbzArchive], which is a sorted-by-index page reader). Both share
 * the same central-directory-parsing and raw-DEFLATE-inflate approach via platform.zlib; kept as
 * a separate small class rather than a shared abstraction since the two access patterns
 * (by name vs. by sorted index) don't overlap enough to be worth a common base.
 */
@OptIn(ExperimentalForeignApi::class)
class IosZipArchive(private val archiveBytes: ByteArray) {

    private data class Entry(
        val name: String,
        val localHeaderOffset: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val compressionMethod: Int,
    )

    private val entriesByName: Map<String, Entry> by lazy {
        parseCentralDirectory().associateBy { it.name }
    }

    /** Returns the decompressed bytes of the entry at [path] (e.g. "META-INF/container.xml"), or null if absent. */
    fun readEntry(path: String): ByteArray? {
        val entry = entriesByName[path] ?: return null
        val dataOffset = localFileDataOffset(entry.localHeaderOffset)
        return if (entry.compressionMethod == COMPRESSION_STORED) {
            archiveBytes.copyOfRange(dataOffset, dataOffset + entry.compressedSize)
        } else {
            inflate(archiveBytes, dataOffset, entry.compressedSize, entry.uncompressedSize)
        }
    }

    fun readEntryAsText(path: String): String? = readEntry(path)?.decodeToString()

    /** Names of every entry in the archive, in central-directory order. */
    fun entryNames(): List<String> = entriesByName.keys.toList()

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

    private fun readInt32LE(offset: Int): Int = (archiveBytes[offset].toInt() and 0xFF) or
        ((archiveBytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((archiveBytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((archiveBytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(offset: Int): Int = (archiveBytes[offset].toInt() and 0xFF) or
        ((archiveBytes[offset + 1].toInt() and 0xFF) shl 8)

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

    private companion object {
        const val COMPRESSION_STORED = 0
    }
}
