package com.riffle.core.data

import java.util.zip.Inflater

/** Reads a slice of a remote/local resource — backed by an HTTP `Range` request or a local file. */
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}

/** One ZIP central-directory record: enough to locate and extract the entry's bytes. */
data class ZipEntryInfo(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val method: Int,
    val localHeaderOffset: Long,
)

/**
 * Parses a ZIP's central directory and extracts individual entries using only byte-range reads,
 * so the audio in a `/synced` bundle is never fetched when all we want is the sidecar (ADR 0028).
 *
 * Hand-rolled (rather than `ZipFile`) because the file isn't local: we read the End-Of-Central-
 * Directory record from the tail, then the central directory, then each wanted entry's local
 * header + compressed data on demand.
 */
class ZipRangeExtractor(
    private val totalSize: Long,
    private val reader: RangeReader,
) {
    fun entries(): List<ZipEntryInfo> {
        val tailLen = minOf(totalSize, EOCD_MAX_SCAN).toInt()
        val tail = reader.read(totalSize - tailLen, tailLen)
        val eocd = lastIndexOf(tail, EOCD_SIGNATURE)
        require(eocd >= 0) { "End-of-central-directory record not found" }
        val cdSize = readIntLE(tail, eocd + 12).toLong()
        val cdOffset = readIntLE(tail, eocd + 16).toLong()

        val cd = reader.read(cdOffset, cdSize.toInt())
        val out = ArrayList<ZipEntryInfo>()
        var p = 0
        while (p + 4 <= cd.size && matches(cd, p, CD_SIGNATURE)) {
            val method = readShortLE(cd, p + 10)
            val compressed = readIntLE(cd, p + 20).toLong()
            val uncompressed = readIntLE(cd, p + 24).toLong()
            val nameLen = readShortLE(cd, p + 28)
            val extraLen = readShortLE(cd, p + 30)
            val commentLen = readShortLE(cd, p + 32)
            val lho = readIntLE(cd, p + 42).toLong()
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            out += ZipEntryInfo(name, compressed, uncompressed, method, lho)
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    fun extract(entry: ZipEntryInfo): ByteArray {
        // Local header: 30 fixed bytes, then name + extra (lengths live in the local header, not the CD).
        val lh = reader.read(entry.localHeaderOffset, LOCAL_HEADER_FIXED)
        val nameLen = readShortLE(lh, 26)
        val extraLen = readShortLE(lh, 28)
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_FIXED + nameLen + extraLen
        val raw = reader.read(dataStart, entry.compressedSize.toInt())
        return when (entry.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> inflate(raw, entry.uncompressedSize.toInt())
            else -> error("Unsupported ZIP method ${entry.method} for ${entry.name}")
        }
    }

    private fun inflate(raw: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(/* nowrap = */ true)
        inflater.setInput(raw)
        val out = ByteArray(expectedSize)
        var off = 0
        while (!inflater.finished() && off < out.size) {
            off += inflater.inflate(out, off, out.size - off)
        }
        inflater.end()
        return out
    }

    private fun matches(b: ByteArray, at: Int, sig: ByteArray) =
        at + sig.size <= b.size && (0 until sig.size).all { b[at + it] == sig[it] }

    private fun lastIndexOf(b: ByteArray, sig: ByteArray): Int {
        for (i in b.size - sig.size downTo 0) if (matches(b, i, sig)) return i
        return -1
    }

    private fun readShortLE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val EOCD_MAX_SCAN = 65_557L // 22-byte record + max 65535 comment
        const val LOCAL_HEADER_FIXED = 30
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
        val CD_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
    }
}
