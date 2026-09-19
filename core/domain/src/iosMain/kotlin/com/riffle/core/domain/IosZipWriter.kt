package com.riffle.core.domain

/**
 * Minimal ZIP writer for iOS — the counterpart to [IosZipArchive]'s reader, used to repackage a
 * Storyteller bundle without its audio entries (the Readaloud sidecar, ADR 0040).
 *
 * Entries are written STORED (uncompressed). The sidecar is SMIL and XHTML that lives briefly in
 * a cache directory, so trading a little disk for not shipping a DEFLATE compressor is the right
 * call; the output is still a spec-valid ZIP — and therefore a valid audio-free EPUB — because the
 * CRC-32s are real. `java.util.zip` on the Android side rejects a wrong CRC, so this must not be
 * faked even though [IosZipArchive] itself does not check it.
 */
object IosZipWriter {

    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val locals = mutableListOf<ByteArray>()
        val centrals = mutableListOf<ByteArray>()
        var offset = 0

        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)

            val local = le32(LOCAL_HEADER_SIGNATURE) + le16(VERSION_NEEDED) + le16(0) + le16(METHOD_STORED) +
                le16(0) + le16(0) + le32(crc) + le32(data.size) + le32(data.size) +
                le16(nameBytes.size) + le16(0) + nameBytes + data
            locals += local

            centrals += le32(CENTRAL_HEADER_SIGNATURE) + le16(VERSION_NEEDED) + le16(VERSION_NEEDED) +
                le16(0) + le16(METHOD_STORED) + le16(0) + le16(0) + le32(crc) +
                le32(data.size) + le32(data.size) + le16(nameBytes.size) + le16(0) + le16(0) +
                le16(0) + le16(0) + le32(0) + le32(offset) + nameBytes

            offset += local.size
        }

        val centralDirectory = if (centrals.isEmpty()) ByteArray(0) else centrals.reduce { a, b -> a + b }
        val eocd = le32(EOCD_SIGNATURE) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) +
            le32(centralDirectory.size) + le32(offset) + le16(0)
        val body = if (locals.isEmpty()) ByteArray(0) else locals.reduce { a, b -> a + b }
        return body + centralDirectory + eocd
    }

    /** Standard CRC-32 (IEEE 802.3), the checksum ZIP entries carry. */
    internal fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFFu
        for (byte in data) {
            crc = crc xor (byte.toUInt() and 0xFFu)
            repeat(8) {
                crc = if (crc and 1u != 0u) (crc shr 1) xor CRC32_POLYNOMIAL else crc shr 1
            }
        }
        return (crc xor 0xFFFFFFFFu).toInt()
    }

    private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50
    private const val EOCD_SIGNATURE = 0x06054B50
    private const val VERSION_NEEDED = 20
    private const val METHOD_STORED = 0
    private val CRC32_POLYNOMIAL = 0xEDB88320u

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )
}
