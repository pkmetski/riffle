package com.riffle.core.catalog.oreilly.epub

/** One file inside the synthesized EPUB archive. */
data class EpubZipEntry(val path: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EpubZipEntry && path == other.path && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/**
 * Minimal, dependency-free ZIP writer used to synthesize EPUB archives in `commonMain`.
 *
 * There is no KMP zip library in the project, and an EPUB is just a ZIP with one hard rule: the
 * first entry MUST be an uncompressed (STORED) `mimetype` file. Every entry here is written STORED
 * (compression method 0), which is fully spec-valid — Readium reads STORED EPUBs identically to
 * DEFLATEd ones — and needs no compression codec. The only computed field is the CRC-32 of each
 * entry, implemented in pure Kotlin below.
 *
 * The caller is responsible for ordering [entries] so `mimetype` is first (see [EpubAssembler]).
 */
object EpubZipWriter {

    private const val LOCAL_FILE_HEADER_SIG = 0x04034b50
    private const val CENTRAL_DIR_SIG = 0x02014b50
    private const val END_OF_CENTRAL_DIR_SIG = 0x06054b50
    private const val VERSION_NEEDED = 20
    private const val METHOD_STORED = 0

    // DOS date/time for 1980-01-01 00:00:00 — the minimum *valid* DOS timestamp. A zero date
    // (month 0 / day 0) is malformed and some strict ZIP readers reject the archive outright.
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x0021 // (year 0 << 9) | (month 1 << 5) | (day 1)

    fun write(entries: List<EpubZipEntry>): ByteArray {
        val out = GrowableBytes()
        val central = GrowableBytes()
        var count = 0

        for (entry in entries) {
            val nameBytes = entry.path.encodeToByteArray()
            val crc = crc32(entry.bytes)
            val size = entry.bytes.size
            val localHeaderOffset = out.size

            // Local file header
            out.putIntLE(LOCAL_FILE_HEADER_SIG)
            out.putShortLE(VERSION_NEEDED)
            out.putShortLE(0)               // general purpose flags
            out.putShortLE(METHOD_STORED)
            out.putShortLE(DOS_TIME)        // mod time (fixed)
            out.putShortLE(DOS_DATE)        // mod date (fixed)
            out.putIntLE(crc)
            out.putIntLE(size)              // compressed size == size (STORED)
            out.putIntLE(size)              // uncompressed size
            out.putShortLE(nameBytes.size)
            out.putShortLE(0)               // extra field length
            out.putBytes(nameBytes)
            out.putBytes(entry.bytes)

            // Central directory record for this entry
            central.putIntLE(CENTRAL_DIR_SIG)
            central.putShortLE(VERSION_NEEDED)   // version made by
            central.putShortLE(VERSION_NEEDED)   // version needed
            central.putShortLE(0)                // flags
            central.putShortLE(METHOD_STORED)
            central.putShortLE(DOS_TIME)         // mod time
            central.putShortLE(DOS_DATE)         // mod date
            central.putIntLE(crc)
            central.putIntLE(size)
            central.putIntLE(size)
            central.putShortLE(nameBytes.size)
            central.putShortLE(0)                // extra length
            central.putShortLE(0)                // comment length
            central.putShortLE(0)                // disk number start
            central.putShortLE(0)                // internal attrs
            central.putIntLE(0)                  // external attrs
            central.putIntLE(localHeaderOffset)
            central.putBytes(nameBytes)
            count++
        }

        val centralDirOffset = out.size
        val centralBytes = central.toByteArray()
        out.putBytes(centralBytes)

        // End of central directory record
        out.putIntLE(END_OF_CENTRAL_DIR_SIG)
        out.putShortLE(0)                   // disk number
        out.putShortLE(0)                   // disk with central dir
        out.putShortLE(count)               // entries on this disk
        out.putShortLE(count)               // total entries
        out.putIntLE(centralBytes.size)     // central dir size
        out.putIntLE(centralDirOffset)      // central dir offset
        out.putShortLE(0)                   // comment length

        return out.toByteArray()
    }

    // ---- CRC-32 (IEEE 802.3 polynomial, reflected) --------------------------

    private val crcTable: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    internal fun crc32(data: ByteArray): Int {
        var crc = 0.inv()
        for (b in data) {
            crc = crcTable[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}

/** Tiny growable byte buffer — avoids boxing every byte through a `List<Byte>`. */
internal class GrowableBytes(initialCapacity: Int = 1024) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var newCap = buf.size * 2
        while (newCap < size + extra) newCap *= 2
        buf = buf.copyOf(newCap)
    }

    fun putBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    fun putShortLE(value: Int) {
        ensure(2)
        buf[size] = (value and 0xFF).toByte()
        buf[size + 1] = ((value ushr 8) and 0xFF).toByte()
        size += 2
    }

    fun putIntLE(value: Int) {
        ensure(4)
        buf[size] = (value and 0xFF).toByte()
        buf[size + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[size + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[size + 3] = ((value ushr 24) and 0xFF).toByte()
        size += 4
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
